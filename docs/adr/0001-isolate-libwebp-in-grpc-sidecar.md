# 0001. libwebp를 별도 프로세스(gRPC 사이드카)로 격리한다

- 상태: accepted
- 날짜: 2026-10-23
- 결정자: Ilkang Na
- 관련 문서: [docs/research/0001-java-webp-landscape.md](../research/0001-java-webp-landscape.md), [docs/research/0004-jdk-baseline.md](../research/0004-jdk-baseline.md), [docs/design/goals-and-constraints.md](../design/goals-and-constraints.md)

## 맥락

JVM에서 WebP를 읽고 쓰는 기존 선택지는 둘로 나뉜다([research/0001](../research/0001-java-webp-landscape.md)).

- 순수 Java(TwelveMonkeys, JDeli): JNI 위험은 없지만 쓰기 미지원(TwelveMonkeys)이거나 상용(JDeli)이다. 벤더(IDR Solutions) 공개 벤치마크에서 JNI(darkxanter)보다 읽기 처리량이 약 18~19배 낮다(측정 환경 비공개).
- JNI + libwebp(sejda, darkxanter, usefulness fork): 빠르지만 libwebp의 메모리 버그가 JVM 전체로 번진다. CVE-2023-4863(libwebp 1.3.2 미만의 힙 오버플로)이 실제 사례이고, sejda와 darkxanter 0.3.2 이하는 그 범위의 libwebp를 번들한다. sejda와 darkxanter는 유지보수가 멈췄다. 활동 중인 usefulness fork도 단순 API(`WebPDecode`/`WebPEncode`)만 쓴다.
- Panama FFM(NightMonkeys): 읽기 전용이고 시스템 libwebp가 필요하며 유지보수 모드다.

ImageIO 플러그인 중 애니메이션 합성과 ICC/EXIF/XMP 읽기·쓰기를 모두 제공하는 것은 확인되지 않았다. JNI 계열은 애니메이션 파일의 첫 프레임조차 읽지 못한다(코드 분석). libwebp에는 이를 위한 상위 API(`WebPAnimDecoder`, `WebPAnimEncoder`, `WebPDemux`, `WebPMux`)가 있다.

## 결정 동인

1. **장애·취약점 격리** — libwebp 메모리 버그가 JVM 프로세스를 죽이거나 JVM 메모리를 오염시키면 안 된다.
2. **기능 범위** — 애니메이션 합성, ICC/EXIF/XMP 읽기·쓰기까지 libwebp 상위 API를 그대로 쓸 수 있어야 한다.
3. **성능** — 순수 Java보다 의미 있게 빨라야 한다.
4. **JVM 산출물의 이식성** — 플러그인 jar에 플랫폼별 네이티브 라이브러리를 넣지 않는다(libc, 아키텍처 의존 제거).
5. **libwebp 패치 주기** — 보안 패치를 서드파티 fork 관리자를 기다리지 않고 바로 적용할 수 있어야 한다.
6. **JDK 방향과의 정합** — JEP 472로 JNI 사용에 제약(경고 → 향후 기본 금지)이 생기고 있다([research/0004](../research/0004-jdk-baseline.md)).
7. **운영 복잡도** — 추가로 배포·감시할 구성 요소가 적을수록 좋다.

## 검토한 선택지

1. JNI (기존 라이브러리 사용 또는 자체 바인딩)
2. Panama FFM(JDK 22+)으로 libwebp를 프로세스 안에서 호출
3. `ProcessBuilder` + CLI(`cwebp`/`dwebp`/`img2webp`/`webpmux`)를 요청마다 실행
4. **libwebp를 링크한 gRPC 서버를 사이드카로 두고 SPI 플러그인이 호출**
5. libwebp를 WebAssembly로 컴파일해 순수 Java Wasm 런타임(예: Chicory)으로 실행
6. 순수 Java만 사용 (TwelveMonkeys + 쓰기는 별도 해결)

## 결정

**선택지 4를 택한다.** libwebp는 별도 프로세스(같은 호스트/파드의 사이드카 컨테이너)에서 gRPC 서버로 실행하고, JVM 쪽은 이 서버를 호출하는 ImageIO SPI 플러그인만 둔다. 이 프로젝트는 이 선택이 맞는지 검증하는 프로토타입이다.

선택지 5(libwebp를 Wasm으로 빌드해 Chicory로 프로세스 안에서 실행)는 **Phase 4 벤치마크에 경쟁 대안으로 포함한다**(결정자 지시, 2026-10-23). 같은 샘플로 사이드카와 처리량·메모리 상한 관리·크래시 격리를 비교한다.

### 결과

- 긍정: libwebp 크래시는 서버 프로세스에서 끝난다. JVM은 `UNAVAILABLE`을 받아 `IIOException`으로 처리한다.
- 긍정: 서버 컨테이너에 메모리/CPU 제한, read-only FS, 네트워크 차단, non-root, seccomp를 적용해 공격 면을 좁힐 수 있다. 네이티브 메모리가 JVM `-Xmx` 밖에서 늘어나는 문제도 컨테이너 제한으로 관리된다.
- 긍정: libwebp 상위 API를 서버 언어에서 직접 쓴다. libwebp 버전은 서버 빌드에서 고정·갱신한다.
- 긍정: 플러그인 jar는 순수 Java라 플랫폼 독립이다. JEP 472 제약과 무관하다.
- 부정: 운영 구성 요소가 하나 늘어난다(사이드카 배포, 헬스체크, 버전 호환).
- 부정: 프로세스 간 복사가 생긴다. 압축 해제된 픽셀(4K 한 장 ≈ 33MB)을 IPC로 옮기는 비용이 속도 이점을 얼마나 깎는지가 이 프로토타입의 핵심 측정 항목이다(Phase 4).
- 부정: 서버 크래시는 그 순간 처리 중인 **모든** 요청을 실패시킨다. 요청 단위 격리는 아니다. 재시작은 오케스트레이터(컨테이너 재시작 정책)가 맡는다.
- 부정: 서버가 없으면 WebP를 전혀 처리할 수 없다(순수 Java 폴백 없음). 폴백 여부는 proto 회고(Phase 5)에서 판단한다.

### 확인 방법

- Phase 1~2: 손상 입력으로 서버를 죽였을 때 JVM이 살아 있고 `IIOException`을 받는지 통합 테스트로 확인한다.
- Phase 4: 같은 샘플로 TwelveMonkeys, darkxanter(JNI), Wasm(Chicory) 대안과 처리량을 비교한다. 사이드카가 순수 Java보다 의미 있게 빠르지 않으면 가설이 틀린 것이다. Wasm 대안이 사이드카와 비슷한 속도를 내면 운영 부담이 적은 쪽을 다시 검토한다.
- Phase 5: 회고 ADR에서 계속/중단을 판단한다.

## 선택지별 장단점

### 1. JNI

- 장점: 가장 빠르다(IPC 없음). 배포 단위가 jar 하나.
- 단점: 네이티브 메모리 버그 = JVM 크래시 또는 RCE. 플랫폼별 네이티브 바이너리 필요. 기존 라이브러리는 유지보수가 멈췄고 상위 API를 쓰지 않는다. JEP 472로 `--enable-native-access` 요구가 강해진다.

### 2. Panama FFM

- 장점: JNI보다 바인딩 작성이 쉽고(jextract), 바인딩을 순수 Java로 쓸 수 있다. 선례로 NightMonkeys(읽기 전용)가 있다.
- 단점: 같은 프로세스 안에서 네이티브 코드를 실행하므로 격리 문제는 JNI와 같다. 네이티브 라이브러리 배포 문제도 그대로다. JDK 22+ 필요(JDK 기준선과 충돌 가능, ADR-0005). 제한된 메서드라 JEP 472의 native access 경고 대상이다.

### 3. ProcessBuilder + CLI

- 장점: 프로세스 격리가 요청 단위로 된다. 구현이 가장 단순하다.
- 단점: 요청마다 프로세스 생성 비용. 표준 CLI는 합성된 애니메이션 프레임을 픽셀로 스트리밍하는 기능이 없어 임시 파일이나 출력 파싱에 의존한다. 호스트에 CLI 설치가 필요하다. 이 프로젝트 목표에서 명시적으로 제외했다.

### 4. gRPC 사이드카 (채택)

- 장점: 위 "결과" 참고. 격리, 기능 범위, jar 이식성, 패치 주기를 모두 만족한다.
- 단점: 운영 구성 요소 추가, IPC 복사 비용, 서버 단위(요청 단위 아님) 장애 반경.

### 5. WebAssembly + 순수 Java 런타임

- 장점: JNI 없이 같은 프로세스에서 libwebp 코드를 실행하면서 Wasm 샌드박스로 메모리를 격리한다. 사이드카가 필요 없다. Chicory는 네이티브 의존성과 JNI가 없는 JVM용 Wasm 런타임이다 [S1].
- 단점: 성능이 미확인이다. Chicory는 스스로 "가장 빠른 런타임"을 목표로 하지 않는다고 밝힌다 [S1]. libwebp를 Wasm으로 빌드하고 유지하는 작업이 필요하다. Wasm 선형 메모리도 JVM 힙(또는 JVM이 관리하는 메모리) 안에 있으므로 메모리 상한 관리가 사이드카보다 어렵다(추정).
- 판단: 사이드카 가설과 직접 경쟁하는 대안이다. Phase 4 벤치마크에 비교 대상으로 넣는다(위 결정).

### 6. 순수 Java만

- 장점: 의존성과 운영 부담이 가장 적다.
- 단점: 쓰기, 애니메이션 합성, 메타데이터 쓰기를 직접 구현해야 하고 속도가 느리다. 이 프로젝트의 목표를 만족하지 않는다. 벤치마크의 기준선으로만 쓴다.

## 참고

- [S1] Chicory — https://github.com/dylibso/chicory (README의 목표/비목표, 확인 2026-10-23)
- 기존 라이브러리 현황과 CVE-2023-4863: [docs/research/0001-java-webp-landscape.md](../research/0001-java-webp-landscape.md)
- JEP 472, FFM: [docs/research/0004-jdk-baseline.md](../research/0004-jdk-baseline.md)
