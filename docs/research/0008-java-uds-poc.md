# 0008. Java 클라이언트 UDS PoC: JNI 없는 경로와 grpc-java 공식 경로

- 상태: 초안
- 작성일/확인일: 2026-10-23
- 관련 ADR: ADR-0003, ADR-0005

## 요약

- **JNI 없는 경로가 동작한다.** JDK `UnixDomainSocketAddress` + Netty NIO(`NioDomainSocketChannel`) + 비셰이드 `grpc-netty` 1.85.1로 서버에 UDS로 붙는다. `--illegal-native-access=deny`로 실행해도 동작한다. ADR-0003의 결정(UDS 기본, JNI 없는 경로 우선)을 유지한다.
- grpc-java 공식 경로(`unix:` target + Netty native epoll)는 JNI 없는 경로보다 약 5–9% 빠르지만, `--illegal-native-access=deny`에서는 쓸 수 없다(native 라이브러리 로드 실패).
- **상호운용 주의**: grpc-java는 UDS 주소에서 소켓 경로를 HTTP/2 `:authority`로 보내는 것으로 보이고, tonic(hyper/h2) 서버는 이를 `PROTOCOL_ERROR`로 거부한다(추정: 경로 문자열이 authority 형식에 맞지 않음). 두 경로 모두 `overrideAuthority("localhost")`가 필요하다.
- JPMS: 런타임 의존성 jar 33개 사이에 split package가 없다. `module-info`로 SPI를 제공할 수 있다.

## 방법

- 서버: `webp-imageio-server:dev`(libwebp 1.6.0), 컨테이너 옵션 `--read-only --cap-drop ALL --security-opt no-new-privileges --memory 1g --cpus 2`, 짧은 `/tmp` 디렉터리를 소켓 디렉터리로 바인드 마운트(AF_UNIX 경로 길이 한도).
- 클라이언트: JDK 25(Temurin 25.0.4.1), grpc-java 1.85.1, Netty 4.2.17.Final. docker CLI로 컨테이너를 띄운다(Testcontainers를 쓰지 않는다. `--illegal-native-access=deny` 실행에 다른 native 의존이 섞이지 않게 하려고).
- 부하: `samples/generated/still/lossless_3840x2160.webp`(3,330 B)를 Decode → RGBA8 33,177,600 B 응답. warmup 5회 후 30회 평균(deny 실행은 warmup 2회, 10회).
- 코드: `client-jvm/src/test/java/net/rivergod/webp/imageio/poc/`. 실행: `./gradlew :client-jvm:udsPoc`, `./gradlew :client-jvm:udsPocDeny`.
- 호스트: Intel Core i9-14900(32 논리 코어), Linux 7.0.0-38, Docker 29.1.3.

## 결과

| 경로 | 채널 구성 | 4K 디코딩 1회 | 처리량 | `--illegal-native-access=deny` |
|---|---|---|---|---|
| A. NIO UDS (JNI 없음) | `NettyChannelBuilder.forAddress(UnixDomainSocketAddress)` + `channelType(NioDomainSocketChannel, UnixDomainSocketAddress)` + `MultiThreadIoEventLoopGroup(NioIoHandler)` | 35.1–37.0 ms | 856–900 MiB/s | 동작(42.4 ms, 746 MiB/s) |
| B. epoll UDS (grpc 공식) | `Grpc.newChannelBuilder("unix://…")` + `netty-transport-native-epoll:linux-x86_64` | 33.9 ms | 933 MiB/s | 불가: `Epoll.isAvailable() = false`(`UnsatisfiedLinkError`), `ProviderNotFoundException: NettyChannelProvider does not support DomainSocketAddress` |
| C. TCP | `127.0.0.1:<게시 포트>`(Docker 포트 게시 경유) | 51.7 ms | 611 MiB/s | (해당 없음) |

- C는 Docker 포트 게시(브리지 네트워크)를 거친 값이라 순수 루프백 TCP보다 불리하다. UDS 대 TCP의 정식 비교는 Phase 4에서 한다.
- 한 번의 실행 결과다. 처리량 수치는 경향만 본다.

## 관찰 사항

1. **`:authority`**: `overrideAuthority`가 없으면 A, B 모두 첫 RPC에서 `INTERNAL: RST_STREAM closed stream. HTTP/2 error code: PROTOCOL_ERROR`가 난다. `overrideAuthority("localhost")`를 넣으면 해결된다. 원인의 세부(grpc-java가 보내는 정확한 값, hyper의 검증 위치)는 미확인.
2. **JEP 472 경고**: `netty-transport-native-epoll`이 클래스패스에 있으면 grpc-netty 초기화 중 Netty `NativeLibraryUtil`이 `System::loadLibrary`를 호출해 JDK 24+ 경고가 난다. 플러그인은 이 jar에 의존하지 않으므로 사용자 앱에서는 이 경고가 나지 않는다(PoC 테스트 클래스패스에만 있다).
3. **`SO_KEEPALIVE` 경고**: A 경로에서 Netty가 `Unknown channel option 'SO_KEEPALIVE' … NioDomainSocketChannel` 경고를 한 번 남긴다. grpc-java가 모든 채널에 이 옵션을 거는 것으로 보인다(추정). 동작에는 영향이 없다.
4. `sun.misc.Unsafe` 관련 경고(JEP 498)는 이번 실행에서 보지 못했다.

## RGBA8 → BufferedImage 변환 비용 (ADR-0004 확인 방법)

- 방법: `client-jvm/src/test/java/net/rivergod/webp/imageio/ConversionCostPocTest.java`. 3840×2160 RGBA8(무작위 바이트)을 1 MiB 조각 32개로 나눠 `ArgbFrameSink`로 `int[]`에 채운다. warmup 20회 후 50회 평균, JDK 25, 같은 호스트.
- 결과: alpha 포함(TYPE_INT_ARGB) 9.85 ms/프레임(1.19 ns/픽셀), alpha 버림(TYPE_INT_RGB) 9.33 ms(1.12 ns/픽셀). 같은 크기(33 MB)의 `System.arraycopy`는 2.23 ms.
- 해석: 위 A 경로의 4K 디코딩 1회(약 35 ms, 서버 디코딩·전송 포함)에서 변환이 차지하는 몫이 작지 않다(추정 약 25%). ADR-0004에 따라 Phase 4에서 전체 처리 시간 대비 비율을 다시 재고, 의미가 있으면 와이어 포맷 추가(ABGR8 등)나 변환 최적화를 검토한다.

## JPMS 확인

- `runtimeClasspath`의 jar 33개를 열어 클래스 패키지를 모은 결과 두 jar 이상에 같은 패키지가 있는 경우가 없다(2026-10-23). grpc-context 1.85.1 jar에는 `io.grpc` 최상위 클래스가 없다.
- grpc-java jar는 `Automatic-Module-Name`(io.grpc, io.grpc.stub, io.grpc.netty, io.grpc.protobuf 등)을 갖고, Netty 4.2와 Guava 33.6은 `module-info`를 갖는다.

## 미확인 항목

- macOS와 Windows에서의 A 경로(JEP 380은 Windows 10+ AF_UNIX를 지원한다고 하지만 이 저장소에서 실행하지 않았다).
- `:authority` 거부의 정확한 원인.
