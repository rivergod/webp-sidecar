# 0003. 전송: UDS 기본 + TCP 옵션, 1 MiB 청크 스트리밍

- 상태: proposed
- 날짜: 2026-10-23
- 결정자: -
- 관련 문서: [ADR-0001](0001-isolate-libwebp-in-grpc-sidecar.md), [ADR-0004](0004-pixel-transfer-format.md), [docs/design/grpc-api.md](../design/grpc-api.md), [docs/research/0004-jdk-baseline.md](../research/0004-jdk-baseline.md) "UDS 지원 경로", [docs/research/0003-server-language-rust-vs-go.md](../research/0003-server-language-rust-vs-go.md) "gRPC 스택"

## 맥락

서버는 같은 호스트/파드의 사이드카다(ADR-0001). 두 가지를 정해야 한다.

1. **전송 계층**: Unix Domain Socket(UDS)과 TCP 중 무엇을 기본으로 할까.
2. **큰 데이터 전달 방식**: 압축 해제된 RGBA는 3840×2160 한 장이 약 33MB로, gRPC 기본 수신 한도 4MB를 넘는다.

관련 사실:

- grpc-java가 문서화한 UDS 경로(`unix:` target)는 Linux에서 Netty native epoll이 있을 때만 동작한다. `grpc-netty-shaded`는 Linux x86_64/aarch64용 epoll native 라이브러리를 번들하고, 초기화 중에 그 로드를 시도한다(소스 기준). JDK 24 이상에서는 JEP 472 경고가 난다([research/0004](../research/0004-jdk-baseline.md)).
- Netty 4.1.110+의 NIO 전송은 JDK 16+ `UnixDomainSocketAddress`로 JNI 없이 UDS를 지원하고, JDK의 AF_UNIX는 Windows 10 / Server 2019 이상에서도 동작한다(JEP 380). 다만 grpc-java가 이 조합을 지원하는지는 **미확인**이다(같은 문서).
- Windows + Docker Desktop에서는 컨테이너 안의 소켓 파일에 호스트 JVM이 접근하지 못할 가능성이 높다(추정, 같은 문서).

## 결정 동인

1. **격리** — 서버 컨테이너에 외부 네트워크가 없어야 한다. 가능하면 네트워크 인터페이스 자체가 없어야 한다.
2. **JVM에 JNI 없음** — ADR-0001의 전제다. 플러그인 코드뿐 아니라 의존성(Netty native transport)도 포함한다.
3. **성능** — 같은 호스트 IPC에서 불필요한 오버헤드가 없어야 한다.
4. **단순함** — 메시지 한도 같은 설정을 양쪽에서 맞춰야 하는 부담이 없어야 한다.
5. **이식성** — Windows 개발 환경에서도 개발과 테스트가 가능해야 한다.

## 검토한 선택지

전송 계층:

- T1. **UDS 기본 + TCP 옵션**
- T2. TCP(루프백) 기본 + UDS 옵션
- T3. UDS만

큰 데이터:

- D1. **고정 상한 크기의 청크 스트리밍** (모든 메시지가 기본 4MB 한도 아래)
- D2. unary + 양쪽 최대 메시지 크기 상향
- D3. gRPC 밖의 공유 메모리(memfd + UDS fd 전달)

## 결정

**T1 + D1을 추천한다.**

전송:

- 서버는 UDS 경로(설정 가능)에서 리슨한다. TCP 리슨은 옵션이고, 켜면 기본 바인드 주소는 `127.0.0.1`이다. 인증과 TLS는 이 프로토타입의 비목표다.
- 클라이언트 기본 엔드포인트는 `unix:///run/webp-imageio/webp.sock`이다(설정 해석은 [grpc-api.md §5](../design/grpc-api.md)).
- **Java 클라이언트의 UDS는 JNI 없는 경로로만 구현한다**: JDK `UnixDomainSocketAddress` + Netty `NioDomainSocketChannel`. 비셰이드 `grpc-netty`를 native transport 없이 쓰거나 Netty native 로드를 끈다. 이 경로는 JDK 16+가 필요하다(ADR-0005의 17 기준선과 맞물린다).
- **Phase 2 첫 작업으로 이 경로의 PoC를 한다.** grpc-java에서 동작하지 않으면 다음 중 하나를 고르는 새 ADR을 쓴다: (a) TCP 루프백을 기본으로 바꾼다. (b) Netty native epoll(JNI)을 허용하고 그 범위를 문서화한다.
- Windows 개발 환경과 크로스 플랫폼 통합 테스트는 TCP를 쓴다. UDS 통합 테스트는 Linux에서 실행한다.

큰 데이터:

- 압축 WebP 바이트와 픽셀 모두 `Limits.max_chunk_bytes` 이하의 조각으로 스트리밍한다. 초기값은 **1 MiB**다. gRPC 기본 한도(4MB)에 충분한 여유를 두면서 메시지 수를 적게 유지하는 출발점이다. Phase 4에서 256 KiB~2 MiB 범위를 측정해 조정한다.
- 어떤 메시지도 기본 한도를 넘지 않게 해서, 클라이언트·서버 어느 쪽도 최대 메시지 크기를 바꾸지 않는다. 한 메시지로 가는 메타데이터(`GetFeaturesResponse`)는 `max_metadata_bytes`로 제한한다.
- 메시지 순서 규칙은 [grpc-api.md §2](../design/grpc-api.md)를 따른다.

### 결과

- 긍정: UDS만 쓰는 배치에서는 서버 컨테이너를 네트워크 없이(`network_mode: none` 등) 띄우고 소켓 파일만 공유 볼륨으로 넘길 수 있다. 접근 제어는 파일 권한으로 한다.
- 긍정: UDS는 TCP/IP 스택을 거치지 않는다(성능 이점의 크기는 Phase 4에서 측정).
- 긍정: 메시지 한도 설정이 필요 없고, 수신 측은 헤더로 크기를 검증한 뒤 조각을 받아 쓴다. 메모리 사용이 조각 단위로 제한된다.
- 부정: grpc-java의 JNI 없는 UDS 경로가 미확인이다. PoC가 실패하면 결정을 다시 해야 한다.
- 부정: Windows에서는 Docker 안 서버로 UDS를 쓸 수 없을 가능성이 높아, 개발 환경과 운영 환경의 전송이 달라진다.
- 부정: 스트리밍 RPC는 unary보다 구현이 복잡하다. 특히 Java에서는 클라이언트/양방향 스트리밍을 동기 API로 감싸는 어댑터가 필요하다([grpc-api.md §8](../design/grpc-api.md)).
- 부정: 청크 조립 때문에 복사가 한 번 더 생길 수 있다.

### 확인 방법

- Phase 2: JNI 없는 UDS PoC. JDK 24+에서 `--illegal-native-access=deny`로 실행해도 동작하면 "JNI 없음"이 성립한다.
- Phase 2: Linux에서 UDS, 모든 플랫폼에서 TCP 통합 테스트 통과.
- Phase 4: UDS vs TCP, 청크 크기별 처리량을 측정해 이 ADR의 초기값을 재검토한다.

## 선택지별 장단점

### T1. UDS 기본 + TCP 옵션 (추천)

- 장점: 네트워크 없는 서버 컨테이너가 가능하다. 성능 이점. 개발·테스트용 TCP 탈출구가 있다.
- 단점: Java 쪽 JNI 없는 UDS가 미확인. 전송이 두 개라 테스트가 두 배.

### T2. TCP 루프백 기본

- 장점: 모든 플랫폼과 grpc-java 기본 경로에서 동작한다. JNI 문제가 없다. 쿠버네티스 파드는 네트워크 네임스페이스를 공유하므로 사이드카 배치에서도 자연스럽다.
- 단점: 서버 컨테이너에 네트워크 인터페이스가 필요하다(compose 등 파드 밖 배치에서 격리가 약해진다). 루프백 TCP 오버헤드.
- 판단: PoC가 실패할 때의 1순위 대안이다.

### T3. UDS만

- 장점: 가장 단순하고 격리가 가장 강하다.
- 단점: Windows 개발과 크로스 플랫폼 통합 테스트가 막힌다.

### D1. 청크 스트리밍 (추천)

- 장점: 크기와 무관하게 같은 경로. 메모리가 조각 단위로 제한된다. 한도 설정이 필요 없다.
- 단점: 스트리밍 구현 복잡도.

### D2. unary + 한도 상향

- 장점: 구현이 가장 단순하다. Java 블로킹 스텁을 그대로 쓴다.
- 단점: 16383×16383 RGBA는 약 1GB다. 한도를 그만큼 올리면 메시지 하나가 양쪽에서 통째로 메모리에 올라간다. 클라이언트와 서버가 한도 설정을 맞춰야 한다.

### D3. 공유 메모리

- 장점: 픽셀 복사를 없앨 수 있다.
- 단점: gRPC 밖의 별도 프로토콜, 플랫폼 의존(memfd는 Linux), 수명 관리가 복잡하다. 측정으로 복사 비용이 지배적이라고 확인될 때만 검토한다.

## 참고

- UDS 경로, JEP 380, JEP 472, Testcontainers on Windows: [docs/research/0004-jdk-baseline.md](../research/0004-jdk-baseline.md)
- 서버 쪽 UDS와 메시지 크기 설정(tonic, grpc-go): [docs/research/0003-server-language-rust-vs-go.md](../research/0003-server-language-rust-vs-go.md)
