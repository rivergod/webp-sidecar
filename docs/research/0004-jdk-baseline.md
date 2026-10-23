# 0004. JDK 최소 버전, 클라이언트 구현 언어, 빌드 도구

- 상태: 초안
- 작성일/확인일: 2026-10-23
- 관련 ADR: ADR-0005

## 요약 (추천안 3~5줄)

- **최소 JDK는 17(`--release 17`)을 추천한다.** 2026-10-23 기준으로 OSS 지원 중인 Spring Boot는 4.0.x, 4.1.x 두 라인이다. 둘 다 최소 Java 17이다. Spring Boot 3.x의 OSS 지원은 3.5.x가 2026-06-30에 끝나면서 모두 끝났다 [S19][S21][S22]. JDK 16에 들어온 `UnixDomainSocketAddress`(JEP 380)와 Netty NIO UDS(4.1.110+)를 쓰면 JNI 없이 Linux, macOS, Windows 10+에서 UDS를 쓸 수 있다. 17은 이 경로를 쓸 수 있는 가장 낮은 LTS다 [S38][S35].
- **구현 언어는 Java를 추천한다.** Kotlin을 쓰면 kotlin-stdlib(2.4.21 기준 약 1.8 MB)가 전이 의존성으로 붙는다. Spring Boot가 관리하는 Kotlin 버전도 라인마다 다르다(3.5: 1.9.25, 4.0: 2.2.21, 4.1: 2.3.21). 그래서 사용자 앱에서 버전 충돌 위험이 생긴다 [S27][S54].
- **빌드 도구는 Gradle(Kotlin DSL)을 약하게 추천한다.** toolchain 자동 프로비저닝으로 17/21/25 테스트 매트릭스를 만들기 쉽고, Google 공식 protobuf 플러그인이 있다. 다만 JMH를 공식 지원하는 빌드 도구는 Maven뿐이고, Gradle JMH 플러그인이 Gradle 9와 호환되는지는 확인하지 못했다. Maven(ascopes protobuf 플러그인 + JMH archetype)도 충분히 실현 가능한 대안이다 [S42][S43][S55][S56][S61].
- JDK 10 이후 `javax.imageio` 공개 API에는 추가가 없다. JDK 26~27의 내부 정리(finalize 제거, AppContext 제거)는 플러그인 설계에 작은 제약만 남긴다(`onDeregistration`에 자원 정리를 맡기지 않는다) [S8][S10][S11][S12][S63].

## 사전 가설 검증 (JDK 9 관련)

| # | 가설 | 검증 결과 | 근거 |
|---|---|---|---|
| 1 | ImageIO는 JDK 9에서 `java.desktop` 모듈로 들어갔다 | **맞다.** `java.desktop`의 module-info는 `@since 9`이고, `javax.imageio`, `.event`, `.metadata`, `.plugins.bmp/jpeg/tiff`, `.spi`, `.stream`을 export한다. 모듈 설명은 "AWT and Swing ... plus APIs for accessibility, audio, imaging, printing, and JavaBeans"다. 모듈 시스템 자체는 JEP 261(Release 9)이다. | [S1][S2] |
| 2 | 모듈 경로에서는 `provides javax.imageio.spi.ImageReaderSpi with ...`로 등록한다 | **맞다.** `java.desktop`은 `uses javax.imageio.spi.ImageReaderSpi`(및 Writer/Transcoder/InputStream/OutputStream SPI)를 선언한다. named module의 provider는 `provides`로 찾는다. public 무인자 생성자나 `public static provider()` 메서드가 있어야 한다. 다른 모듈의 provider는 `provides`에 쓸 수 없다. | [S2][S3] |
| 3 | 클래스패스에서는 `META-INF/services`로 찾는다 | **맞다.** `IIORegistry`는 생성될 때(처음 ImageIO를 쓸 때) `registerApplicationClasspathSpis()`를 호출한다. 이 메서드는 SPI 범주마다 `ServiceLoader.load(c, Thread.currentThread().getContextClassLoader())`를 돈다. `ImageIO.scanForPlugins()`도 같은 메서드를 다시 부른다. | [S3][S4][S63] |
| 4 | 모듈 JAR에 `provides`와 `META-INF/services`를 함께 두면 충돌한다 | **충돌하지 않는다.** ServiceLoader는 named module에 배포된 provider가 provider-configuration 파일에 적혀 있으면 그 항목을 무시해 중복을 막는다. 모듈 경로와 클래스패스를 모두 지원하려면 둘 다 넣는 것이 맞다. 자동 모듈(automatic module)은 provider 메서드를 지원하지 않으므로 provider 생성자가 있어야 한다. | [S3] |
| 5 | JEP 262로 TIFF 플러그인이 JDK 9에 들어왔다 | **맞다.** Closed/Delivered, Release 9이다. 패키지는 `javax.imageio.plugins.tiff`다. JDK 25 소스에서 `@since 9`가 붙은 `javax.imageio` 타입 13개는 모두 이 패키지에 있다. | [S6][S63] |
| 6 | (추가) JDK 9의 다른 ImageIO 변화 | 두 가지를 확인했다. (a) JDK-8068749(Fix 9): `ServiceRegistry`가 ImageIO SPI 다섯 종류만 범주로 받는다. 다른 타입은 `IllegalArgumentException`이다. (b) `javax.imageio.stream`과 `ServiceRegistry`의 `finalize()`가 `@Deprecated(since="9", forRemoval=true)`가 됐다. | [S7][S9][S63] |

설계에 남는 시사점은 세 가지다.

- `ServiceRegistry` javadoc은 SPI 클래스를 가볍게 만들라고 권한다. 복잡한 의존성과 native code를 피하고, 무거운 서비스는 가벼운 proxy로 등록하라고 한다. 따라서 gRPC 채널은 SPI 생성 시점이 아니라 처음 실제로 읽거나 쓸 때 lazy하게 만드는 편이 맞다 [S63].
- registry는 처음 ImageIO를 쓰는 스레드의 context class loader로 클래스패스를 스캔한다. 그래서 서블릿 컨테이너에 WAR로 배포하고 플러그인을 `WEB-INF/lib`에 두면 발견되지 않거나, 재배포 후 class loader가 누수될 수 있다. TwelveMonkeys는 이 문제 때문에 `IIOProviderContextListener`를 둔다 [S17]. Spring Boot 실행형 JAR에서는 문제가 덜하다고 보지만, 확인하지는 않았다(추정).
- `ImageReaderSpi.STANDARD_INPUT_TYPE`과 `ImageWriterSpi.STANDARD_OUTPUT_TYPE`은 deprecated다. `new Class<?>[] { ImageInputStream.class }`를 직접 쓴다 [S9][S63].

## JDK 9 이후 ImageIO 변화

공개 API에 추가는 없다.

- 로컬 Temurin 25.0.4.1의 `src.zip`에서 `javax.imageio` 전체의 `@since`를 집계했다. 1.4, 1.5, 1.6, 9만 나오고 10 이상은 없다 [S63].
- Oracle "New API" 목록(JDK 17, 21, 25, 26, 27판)에도 `javax.imageio` 항목이 없다. JDK 25판은 JDK 12~25, 27판은 그 이후까지 다룬다 [S8].

동작과 내부 변화는 다음과 같다.

| JDK | 변화 | 플러그인 영향 | 근거 |
|---|---|---|---|
| 18 | JEP 421: finalization을 제거 예정으로 deprecate | 없음 | [S15] |
| 24 | JEP 486: Security Manager 영구 비활성화. java.desktop의 imageio 코드에서 `doPrivileged`가 제거됨 | 없음. `ServiceRegistry` 문서의 "runtime permission" 문구는 의미를 잃는다 | [S14][S64] |
| 26 | JDK-8277585, JDK-8365292: `javax.imageio.stream` 클래스와 `ServiceRegistry`에서 `finalize()` 제거 | 있음(작음). GC로 registry가 정리될 때 provider의 `onDeregistration`이 더는 호출되지 않는다. gRPC 채널 종료를 `onDeregistration`이나 finalize에 맡기지 않는다 | [S10][S11] |
| 26 | JEP 504: Applet API 제거. `IIORegistry.getDefaultInstance()` 문서에서 "Each ThreadGroup will receive its own instance" 문구가 빠짐 | 없음. registry를 VM 전역 하나로 본다 | [S4][S5][S13] |
| 27 | JDK-8375350: imageio 구현에서 `AppContext` 사용 제거 | 없음 | [S12][S64] |

결론적으로 ImageIO SPI 관점에서는 JDK 9 이후 기능 요구가 없다. 최소 JDK는 Spring, gRPC/UDS, LTS 일정이 결정한다.

참고로 JDK 26은 2026-03-17, JDK 27은 2026-09-15에 GA됐다. 최신 LTS는 2025-09-16에 GA된 JDK 25다 [S16].

## Spring 지원 현황

spring.io 지원 표의 원천인 `api.spring.io` generations 데이터와 각 system requirements 페이지를 기준으로 정리했다. 지원 표의 날짜는 월말 단위로 적혀 있다 [S19][S20].

| 라인 | 최초 릴리스 | Java 범위 | OSS 지원 종료 | 상용 지원 종료 | 근거 |
|---|---|---|---|---|---|
| Spring Boot 2.7.x | 2022-05 | 8 ~ 21 | 2023-06-30 | 2029-06-30 | [S19][S24] |
| Spring Boot 3.5.x | 2025-05 | 17 ~ 25 | **2026-06-30 (종료)** | 2032-06-30 | [S19][S23] |
| Spring Boot 4.0.x | 2025-11 (4.0.0: 2025-11-20) | 17 ~ 27 | 2026-12-31 | 2027-12-31 | [S19][S22][S26] |
| Spring Boot 4.1.x (현재 4.1.2) | 2026-06 | 17 ~ 27 | 2027-07-31 | 2028-07-31 | [S19][S21] |
| Spring Boot 4.2.x | 2026-11 (예정) | 미확인 | 2027-12-31 | 2028-12-31 | [S19] |
| Spring Framework 5.3.x | 2020-10 | 8 ~ 21 | 2024-08-31 | 2029-06-30 | [S18][S20] |
| Spring Framework 6.2.x | 2024-11 | 17 ~ 25 | 2026-06-30 (종료) | 2032-06-30 | [S18][S20] |
| Spring Framework 7.0.x | 2025-11-13 GA | 17 ~ 25+ | 2027-07-31 | 2028-07-31 | [S18][S20][S25] |
| Spring Framework 7.1.x | 2026-11 (예정) | 미확인 | 2027-12-31 | 2028-12-31 | [S20] |

- Spring Boot 3.0~3.4의 OSS 지원도 모두 끝났다. 2026-10-23 기준 OSS 지원 중인 라인은 Boot 4.0.x와 4.1.x뿐이다 [S19].
- "Java 17 baseline, Java 25 focus" 주장은 맞다. Spring Framework 7.0 GA 공지는 "Embracing Java 25 (as the latest LTS) while retaining a Java 17 baseline"이라고 쓴다. Kotlin 2.2와 Jakarta EE 11이 새 기준선이다 [S25].
- Spring Boot 2.x(Java 8)는 OSS 지원이 2023-06-30에 끝났다. 2.7.x만 상용 지원이 2029-06까지 남아 있다. 신규 라이브러리의 주 대상에서는 빼도 된다고 본다. 다만 레거시 기업 사용자가 있을 수 있다(추정).
- Spring 쪽에서 가장 낮은 현행 지원 JDK는 **17**이다. Boot 3.5/4.x와 Framework 6.2/7.x가 모두 같다. "Spring 최저 지원 JDK보다 높게 잡지 않는다"는 기본 방침에 따르면 상한도 17이다.
- 의존성 관리에 참고할 점이 있다. Spring Boot 4.1은 gRPC 지원(Spring gRPC 1.1)을 추가했다. 그래서 BOM이 `io.grpc:*` 1.84.1과 `protobuf-java` 4.36.2를 관리한다. Boot 4.x는 Netty 4.2.19, Boot 3.5는 Netty 4.1.135를 관리한다. 사용자의 BOM이 플러그인이 끌어온 grpc/protobuf 버전을 덮어쓸 수 있다. 따라서 호환 범위를 정하고 Boot BOM과 함께 테스트해야 한다 [S27][S28].

## 의존 라이브러리 요구사항 (gRPC-java, UDS, Testcontainers, JMH)

### gRPC-java

- 최소 JDK는 지금도 **Java 8**이다. README는 "supports Java 8 and later"라고 쓰고, 최신은 1.85.1(2026-10-20)이다. 1.84.0(2026-09)은 오히려 grpc-context를 Java 8 바이트코드로 올렸다. grpc-core, grpc-api 1.85.1과 protobuf-java 4.36.2의 클래스 파일 major는 52(Java 8)다 [S29][S31][S41].
- gRFC P5는 Oracle Premier Support가 끝난 JDK를 "drop할 수 있다"고 정했고, 2022년에 "Java 8 may be dropped"라고 예상했다. 그러나 Java 8을 실제로 drop하겠다는 공지는 찾지 못했다. **사전 가설("Java 8 지원 중단 예정")은 현재 근거가 없다** [S30][S31].
- 1.83.0(2026-07)부터 Netty 4.2를 쓴다. 1.85.0은 Netty 4.2.17이다 [S31].

### UDS 지원 경로

| 경로 | 주소 타입 | 플랫폼 | 네이티브(JNI) | grpc-java 공식 경로 여부 | 근거 |
|---|---|---|---|---|---|
| `unix:` target → `UdsNameResolverProvider` + `UdsNettyChannelProvider` | Netty `io.netty.channel.unix.DomainSocketAddress` | Linux(epoll) | 예 | 예. `UdsNettyChannelProvider.isAvailable()`는 epoll domain socket 채널 타입이 있을 때만 true다 | [S32] |
| `NettyChannelBuilder.forAddress(DomainSocketAddress)` + `KQueueDomainSocketChannel` | Netty `DomainSocketAddress` | macOS | 예 | 문서 없음. 비셰이드 `grpc-netty`에 `netty-transport-native-kqueue`를 추가해 수동 구성해야 한다(추정) | [S33][S34] |
| `NettyChannelBuilder.forAddress(UnixDomainSocketAddress)` + `channelType(NioDomainSocketChannel.class, UnixDomainSocketAddress.class)` + NIO event loop | JDK `java.net.UnixDomainSocketAddress` | Linux, macOS, Windows 10+/Server 2019+ | 아니오 | **미확인.** grpc-java 문서와 이슈에서 언급을 찾지 못했다. API 조합으로는 가능해 보인다(추정, PoC 필요) | [S34][S35][S36][S38] |

- `grpc-netty-shaded`는 `netty-transport-native-epoll`을 linux-x86_64와 linux-aarch_64 classifier로만 번들한다. kqueue는 번들하지 않는다. tcnative는 linux, osx, windows용을 번들한다 [S33].
- Netty NIO 전송의 UDS 지원은 4.1.110.Final(2024-05)에서 추가됐다. Java 16 이상이 필요하고, 미만이면 `UnsupportedOperationException`이 난다. `NioDomainSocketChannel`은 주소를 JDK `SocketChannel.connect`에 그대로 넘기므로, JDK `UnixDomainSocketAddress`를 받는다 [S35][S36].
- JEP 380(JDK 16)은 "Unix-domain sockets are now supported in Windows 10 and Windows Server 2019"라고 명시한다 [S38].
- **"No JNI" 원칙과의 관계(중요).** Linux에서 grpc-netty-shaded를 쓰면 `NettyChannelBuilder`의 static 필드가 `Utils`를 초기화하고, `Utils`는 `Epoll.isAvailable()`을 호출해 epoll 가용성을 확인한다. 이때 epoll native 라이브러리 로드가 시도된다(소스 기준, 실행 미검증). 그러면 JDK 24 이상에서 JEP 472 경고가 나고, 장래에는 deny가 기본이 된다. Netty `NativeLibraryUtil`에서 이 경고가 실제로 나는 사례도 보고돼 있다 [S32][S46][S49]. 따라서 플러그인 코드에 JNI가 없어도 의존성 쪽에서 JNI가 들어온다.
- 완전한 JNI-free를 원하면 선택지는 두 가지다. (a) 비셰이드 `grpc-netty`를 native transport 없이 쓴다. (b) Netty의 `io.netty.transport.noNative=true`로 native를 끈다. 셰이드판에서 이 속성 이름도 relocate되는지는 미확인이다 [S37]. 둘 중 어느 쪽이든 NIO UDS 경로를 쓴다. 이 경로는 JDK 16 이상을 요구한다. 17을 기준선으로 고르는 기술적 근거가 여기에 있다.
- Windows 개발 환경에서는 사이드카 서버가 Docker Desktop(Linux VM) 안에 있다. 이 경우 컨테이너 안의 소켓 파일은 Windows 호스트 JVM에서 AF_UNIX로 접근할 수 없을 가능성이 높다(추정). 테스트에서는 TCP를 쓰거나, 서버를 Windows 네이티브로 띄워야 한다.

### Testcontainers (Java)

- 최신은 2.0.5다. 2.0.0의 주요 변경은 JUnit 4 지원 제거, 모듈명 `testcontainers-` 접두사, 컨테이너 클래스의 패키지 이동이다. 릴리스 노트와 지원 환경 문서에는 최소 Java 버전이 명시돼 있지 않다 [S39][S40].
- 2.0.5와 1.21.4 JAR의 클래스 파일 major는 모두 52(Java 8)다. 따라서 2.x도 Java 8 이상에서 돈다고 추정한다(추정) [S41]. 우리 기준선(17)과는 충돌하지 않는다.
- Spring Boot 4.x는 Testcontainers 2.0.5를, 3.5는 1.21.4를 관리한다 [S27].
- Windows 문서 내용은 다음과 같다. Docker Desktop은 named pipe로 통신한다. WSL2 백엔드는 "should work out of the box"이고 Windows 10 2004 이상에서 Beta로 표시된다. Windows 컨테이너(WCOW)와 Windows Server 2019의 Docker는 지원하지 않는다 [S40].

### JMH

- 최신은 1.37(2023-08)이고 바이트코드는 Java 8이다. JMH가 공식 지원하는 빌드 도구는 **Maven뿐**이다. README는 `jmh-java-benchmark-archetype`로 독립 프로젝트를 만드는 방식을 권장한다. Gradle과 SBT 플러그인은 "supported by community"다 [S41][S42].
- Gradle 플러그인 `me.champeau.jmh`의 최신은 0.7.3(2025-01)이고 기본 JMH 버전은 1.37이다. 호환표의 최신 행은 Gradle 8.0 → 0.7.0이다. **Gradle 9.x와 호환되는지는 미확인이다** [S43].

## JDK 신기능 영향 (가상 스레드, JEP 472, FFM)

- **가상 스레드(JEP 444, JDK 21)는 필요 없다.** ImageIO API는 blocking이고, grpc-java blocking stub은 Java 8 이상 어디서나 동작한다. 사용자가 가상 스레드에서 플러그인을 호출하는 것은 막을 이유가 없다. JEP 444는 오래 걸리는 I/O를 `synchronized`로 감싸면 pinning이 생긴다고 경고하고, 비싼 자원을 thread local로 풀링하지 말라고 권한다. JDK 24의 JEP 491은 `synchronized` pinning을 없앴다. 다만 native 메서드나 FFM 호출 중 Java로 콜백해 block되면 여전히 pinning된다 [S44][S45]. 그래서 17 기준으로 만들되 이 권고(긴 I/O를 `synchronized`로 감싸지 않기, thread local 풀링 피하기)를 지키면 21+ 가상 스레드 환경과도 호환된다.
- **JEP 472(JDK 24, Prepare to Restrict the Use of JNI)는 이 설계의 장점이다.** JDK 24부터 `System.load(Library)`와 native 메서드의 첫 바인딩에 경고가 나고, `--illegal-native-access`의 기본값은 `warn`이다. "a future JDK release will throw exceptions by default"라고 예고돼 있다. JNI 라이브러리 사용자는 `--enable-native-access` 설정을 떠안는다 [S46][S48]. 사이드카 방식은 사용자 JVM에 이 플래그를 요구하지 않는다. 단, 위 UDS 절에서 본 것처럼 Netty native transport를 피해야 이 장점이 유지된다.
- **FFM API(JEP 454, JDK 22 final)는 대안이지만 격리 수준이 다르다.** FFM은 JNI 없이 순수 Java로 libwebp를 in-process 호출할 수 있게 해 준다. 그러나 `Linker.downcallHandle` 같은 restricted 메서드는 JNI와 똑같이 native access 경고와 플래그 대상이다. libwebp 결함이 JVM을 함께 죽이는 위험도 그대로다. 또 JDK 22+를 요구하므로 17 기준선과 맞지 않는다 [S47][S48]. 외부 프로세스 격리(ADR-0001)를 대체하지 못한다.

## LTS 지원 기간

| JDK | GA | Oracle Premier | Oracle Extended | Temurin (최소) | 근거 |
|---|---|---|---|---|---|
| 11 | 2018-09 | 2023-09 (종료) | 2032-01 | 2027-10 | [S50][S51] |
| 17 | 2021-09 | **2026-09 (종료)** | 2029-09 | 2027-10 | [S50][S51] |
| 21 | 2023-09 | 2028-09 | 2031-09 | 2029-12 | [S50][S51] |
| 25 | 2025-09 | 2030-09 | 2033-09 | 2031-09 | [S50][S51] |
| 26 (non-LTS) | 2026-03 | 2026-09 | 없음 | 2026-09 | [S50][S51] |
| 27 (non-LTS) | 2026-09 | 2027-03 | 없음 | 2027-03 | [S50][S51] |

- Oracle은 다음 LTS를 29(2027-09 예정)로 표시한다. Oracle JDK의 무료(NFTC) 업데이트에 대해서는 "move to Oracle JDK 25 or later"라고 안내한다. Oracle JDK 21은 2026-10-20 릴리스부터 OTN 라이선스다 [S50].
- 17을 기준선으로 고를 때의 리스크는 기간이다. Temurin 17은 최소 2027-10까지, Oracle Premier는 이미 끝났다. 기준선을 21로 올릴지 2027년 상반기에 다시 검토하는 것이 맞다고 본다(추정).

## 구현 언어: Java vs Kotlin

| 항목 | Java | Kotlin |
|---|---|---|
| 런타임 전이 의존성 | 없음 | `kotlin-stdlib` 2.4.21 JAR 약 1.8 MB(1,810 KiB)와 `org.jetbrains:annotations:13.0`. 참고로 grpc-netty-shaded 1.85.1은 약 10.4 MB다 [S54] |
| 사용자 Kotlin과의 충돌 | 없음 | Boot 3.5는 kotlin-stdlib 1.9.25, 4.0은 2.2.21, 4.1은 2.3.21을 관리한다. 최신 stable은 2.4.21(2026-10-08)이다. Boot BOM이 stdlib를 낮추면, 새 컴파일러로 만든 라이브러리가 새 stdlib API를 참조해 런타임 오류가 날 수 있다. `apiVersion`을 낮춰 고정해야 한다(추정) [S27][S52][S54] |
| Java 사용자 interop | 자연스럽다 | Kotlin에는 checked exception이 없다. ImageIO의 `IOException`을 Java 호출자가 잡으려면 `@Throws`가 필요하다. 정적 멤버에는 `@JvmStatic`, 기본 인자에는 `@JvmOverloads`를 일일이 붙여야 한다. public 함수에는 null 검사가 삽입된다 [S53] |
| ImageIO SPI 적합성 | SPI는 Java API(protected 필드, 추상 클래스 상속)이고 기존 플러그인도 Java다 | 가능하지만 이득이 적다 |
| gRPC 코드 생성 | protoc-gen-grpc-java로 끝난다 | grpc-kotlin을 쓰면 coroutines 의존성이 추가로 붙는다(추정). Java 생성 코드를 그대로 쓰면 Kotlin의 장점이 줄어든다 |
| Spring 친화성 | Spring Framework 7은 Kotlin 2.2를 baseline으로 지원한다. Kotlin 사용자도 Java 라이브러리를 쓰는 데 문제가 없다 | 같음 [S25] |

결론적으로 라이브러리 본체는 Java로 쓴다. 빌드 스크립트에 Gradle Kotlin DSL을 쓰는 것은 산출물에 영향이 없다.

## 빌드 도구: Gradle vs Maven

| 항목 | Gradle | Maven |
|---|---|---|
| 현재 버전과 실행 JDK | 9.8.2. Gradle 실행에는 JVM 17~27이 필요하다. Java 25는 9.1.0부터, 27은 9.8.0부터 지원한다 [S55] | 3.9.16(2026-05, Java 8 실행). 4.0은 아직 GA가 아니다(4.0.0-rc-7, 2026-09, Java 17 요구) [S57] |
| 낮은 버전으로 컴파일 | toolchain + `options.release`. 컴파일과 테스트는 "JDK 6 and above"를 쓸 수 있다. foojay resolver로 JDK를 자동 다운로드한다 [S55][S56] | `maven.compiler.release`(`--release`, JDK 9+ javac). toolchain은 미리 설치한 JDK와 `toolchains.xml`이 필요하다(자동 다운로드 기능은 미확인) [S58] |
| protobuf/gRPC 코드 생성 | Google `com.google.protobuf` 0.10.0(2026-04). Gradle 7.6+, Java 11+가 필요하다. `protoc-gen-grpc-java`를 artifact로 지정한다 [S60] | `io.github.ascopes:protobuf-maven-plugin` 5.1.11(2026-09-30). Maven 3.9+, Java 17+가 필요하다. OS별 protoc와 `protoc-gen-grpc-java`(`kind="binary-maven"`)를 자동으로 해결한다. Windows x86_64/x86, Windows ARM은 x86_64 에뮬레이션으로 지원한다. 구식 조합인 xolstice 0.6.1(2018)+os-maven-plugin 1.7.1(2022)은 정체 상태다 [S61][S62] |
| JMH | community 플러그인 `me.champeau.jmh` 0.7.3. Gradle 9 호환은 미확인이다 [S43] | 공식 지원. archetype과 `benchmarks.jar` 방식 [S42] |
| 멀티 모듈 | 가능(settings.gradle.kts, version catalog) | 가능(reactor, parent POM) |
| Windows | `gradlew.bat` | Maven Wrapper 3.3.4 `mvnw.cmd`. 기본 `only-script`는 Windows에서 PowerShell로 내려받는다 [S59] |
| Spring 사용자 측 | Boot 4.x 플러그인은 Gradle 8.14+ 또는 9.x를 요구한다 [S21] | Boot 4.x는 Maven 3.6.3+를 요구한다 [S21] |

판단 근거는 다음과 같다.

- 라이브러리 소비자는 어느 도구로 빌드했는지와 무관하다. 그러므로 개발 편의로 고른다.
- Gradle을 고를 이유는 세 가지다. Windows에서도 JDK를 따로 설치하지 않고 toolchain으로 17/21/25/27 매트릭스를 돌릴 수 있다. Google 공식 protobuf 플러그인이 있다. 멀티 모듈(proto, client, it, bench)을 한 스크립트 체계로 다룰 수 있다.
- Maven을 고를 이유도 세 가지다. JMH 공식 경로를 쓴다. ascopes 플러그인이 protoc를 자동으로 관리한다. 설정이 선언적이라 단순하다.
- Gradle의 리스크는 JMH 플러그인의 Gradle 9 호환이 미확인이라는 점이다. 문제가 생기면 별도 source set과 `jmh-generator-annprocess`로 직접 구성할 수 있다고 본다(추정).

## 추천안

| 결정 | 추천 | 핵심 이유 | 버린 대안 |
|---|---|---|---|
| (a) 최소 JDK(release target) | **17** (`--release 17`). 빌드는 JDK 25 toolchain으로 하고, CI는 17/21/25 + 최신(27)으로 테스트한다 | (1) OSS 지원 중인 Spring Boot 4.0/4.1, Framework 7.0과 상용 지원 중인 Boot 3.5, Framework 6.2의 공통 최소가 17이다 [S18][S19][S21]~[S23]. (2) JDK 16+ `UnixDomainSocketAddress`와 Netty NIO UDS로 JNI 없는 UDS를 Windows 10+까지 쓸 수 있다 [S35][S38]. (3) ImageIO는 9 이후 요구가 없다 [S63]. (4) gRPC, Testcontainers, JMH가 모두 Java 8 바이트코드라 제약이 없다 [S41] | **11**: JEP 380이 없어 UDS가 epoll/kqueue native(JNI)에 묶이고, 이득을 보는 대상은 OSS 지원이 끝난 Boot 2.7뿐이다. **21**: 가상 스레드가 필요 없고, Boot 3.x/4.x의 17 런타임 사용자를 배제한다. **25**: 기본 방침(Spring 최저 지원 JDK 이하)에 어긋난다 |
| (b) 구현 언어 | **Java** | stdlib 전이 의존성(약 1.8 MB)과 Boot 라인별 Kotlin 버전 충돌을 피한다. ImageIO SPI와 Java 사용자 interop이 자연스럽다 [S27][S53][S54] | Kotlin: 사용자 측 비용에 비해 이득이 작다 |
| (c) 빌드 도구 | **Gradle(Kotlin DSL)**, 약한 추천 | toolchain 매트릭스와 JDK 자동 프로비저닝, 공식 protobuf 플러그인 [S55][S56][S60] | Maven: JMH 공식 지원과 ascopes 플러그인 덕분에 충분히 대안이 된다. JMH Gradle 플러그인이 Gradle 9에서 문제가 되면 Maven으로 전환하는 것을 다시 검토한다 |

ADR-0005로 넘길 후속 작업은 다음과 같다.

- PoC로 grpc-java가 NIO `NioDomainSocketChannel`과 `UnixDomainSocketAddress`로 Linux와 Windows에서 연결되는지 확인한다. `--illegal-native-access=deny`에서도 동작하는지 함께 본다.
- 셰이드 여부와 grpc/protobuf 버전 정책을 정한다. Boot 4.1 BOM과 함께 테스트한다.

최종 결정은 사람이 한다.

## 미확인 항목

- grpc-java가 JDK `UnixDomainSocketAddress`와 Netty `NioDomainSocketChannel` 조합을 공식 지원하거나 테스트하는지. 이슈 검색 결과는 0건이었다. authority 처리(`overrideAuthority`가 필요한지 등)도 확인하지 못했다.
- grpc-netty-shaded에서 `io.netty.transport.noNative` 속성 이름이 relocate되는지, 그리고 셰이드판에서 native epoll 로드를 확실히 막는 방법.
- macOS kqueue로 UDS를 구성하는 공식 가이드. grpc-java 문서에서는 찾지 못했다.
- Testcontainers 2.x의 공식 최소 Java 버전. 바이트코드로 Java 8이라고 추정만 했다.
- `me.champeau.jmh` 0.7.3과 Gradle 9.x의 호환성.
- Maven toolchains에 JDK 자동 다운로드 기능이 있는지.
- Docker Desktop(Windows/macOS)에서 bind mount한 UDS 소켓 파일을 호스트 JVM이 쓸 수 있는지(현재 판단은 "불가 가능성 높음", 추정).
- Spring Boot 4.2와 Spring Framework 7.1의 Java 지원 범위. 둘 다 아직 GA 전이다.
- Spring Boot 실행형 JAR(nested JAR class loader)에서 ImageIO 플러그인이 처음 스캔될 때 TCCL이 어떤 상태인지.

## 출처

- [S1] https://openjdk.org/jeps/261 — JEP 261 Module System, Closed/Delivered, Release 9 (확인 2026-10-23)
- [S2] 로컬 Temurin 25.0.4.1 `/usr/lib/jvm/temurin-25-jdk-amd64/lib/src.zip` → `java.desktop/module-info.java` (L26-56 문서와 `@since 9`, L80-87 exports, L131-135 uses) (확인 2026-10-23)
- [S3] https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/util/ServiceLoader.html — named module/클래스패스 provider 탐색, 중복 무시 규칙 (확인 2026-10-23)
- [S4] https://docs.oracle.com/en/java/javase/25/docs/api/java.desktop/javax/imageio/spi/IIORegistry.html — "Each ThreadGroup will receive its own instance" (확인 2026-10-23)
- [S5] https://docs.oracle.com/en/java/javase/26/docs/api/java.desktop/javax/imageio/spi/IIORegistry.html , https://docs.oracle.com/en/java/javase/27/docs/api/java.desktop/javax/imageio/spi/IIORegistry.html — ThreadGroup 문구 삭제 (확인 2026-10-23)
- [S6] https://openjdk.org/jeps/262 — TIFF Image I/O, Release 9 (확인 2026-10-23)
- [S7] https://bugs.openjdk.org/browse/JDK-8068749 — Restrict ServiceRegistry to ImageIO types, Fix 9 (확인 2026-10-23)
- [S8] https://docs.oracle.com/en/java/javase/{17,21,25,26,27}/docs/api/new-list.html — `javax.imageio` 항목 없음(HTML grep) (확인 2026-10-23)
- [S9] https://docs.oracle.com/en/java/javase/25/docs/api/deprecated-list.html , https://docs.oracle.com/en/java/javase/26/docs/api/deprecated-list.html (확인 2026-10-23)
- [S10] https://bugs.openjdk.org/browse/JDK-8277585 — finalize() 제거(javax.imageio.stream), Fix 26 (확인 2026-10-23)
- [S11] https://bugs.openjdk.org/browse/JDK-8365292 — ServiceRegistry.finalize() 제거, Fix 26 (확인 2026-10-23)
- [S12] https://bugs.openjdk.org/browse/JDK-8375350 — imageio의 AppContext 사용 제거, Fix 27 (확인 2026-10-23)
- [S13] https://openjdk.org/jeps/504 — Remove the Applet API, Release 26 (확인 2026-10-23)
- [S14] https://openjdk.org/jeps/486 — Permanently Disable the Security Manager, Release 24 (확인 2026-10-23)
- [S15] https://openjdk.org/jeps/421 — Deprecate Finalization for Removal, Release 18 (확인 2026-10-23)
- [S16] https://openjdk.org/projects/jdk/25/ , https://openjdk.org/projects/jdk/26/ , https://openjdk.org/projects/jdk/27/ — GA 2025-09-16 / 2026-03-17 / 2026-09-15 (확인 2026-10-23)
- [S17] https://github.com/haraldk/TwelveMonkeys — README, 웹앱 배포와 `IIOProviderContextListener` (확인 2026-10-23)
- [S18] https://github.com/spring-projects/spring-framework/wiki/Spring-Framework-Versions — 5.3.x JDK 8-21, 6.2.x JDK 17-25, 7.0.x JDK 17-25+ (확인 2026-10-23)
- [S19] https://spring.io/projects/spring-boot#support , 데이터 원천 https://api.spring.io/projects/spring-boot/generations (확인 2026-10-23)
- [S20] https://spring.io/projects/spring-framework#support , 데이터 원천 https://api.spring.io/projects/spring-framework/generations (확인 2026-10-23)
- [S21] https://docs.spring.io/spring-boot/system-requirements.html — 4.1.2: Java 17~27, Framework 7.0.10+, Maven 3.6.3+, Gradle 8.14+/9.x (확인 2026-10-23)
- [S22] https://docs.spring.io/spring-boot/4.0/system-requirements.html — 4.0.9: Java 17~27 (확인 2026-10-23)
- [S23] https://docs.spring.io/spring-boot/3.5/system-requirements.html — 3.5.16: Java 17~25, Framework 6.2.19+ (확인 2026-10-23)
- [S24] https://docs.spring.io/spring-boot/docs/2.7.x/reference/html/getting-started.html — 2.7.18: Java 8, 21까지 호환 (확인 2026-10-23)
- [S25] https://spring.io/blog/2025/11/13/spring-framework-7-0-general-availability/ — Java 17 baseline, Java 25, Kotlin 2.2, Jakarta EE 11 (확인 2026-10-23)
- [S26] https://spring.io/blog/2025/11/20/spring-boot-4-0-0-available-now/ (확인 2026-10-23)
- [S27] https://docs.spring.io/spring-boot/appendix/dependency-versions/coordinates.html (4.1.2), https://docs.spring.io/spring-boot/4.0/appendix/dependency-versions/coordinates.html (4.0.9), https://docs.spring.io/spring-boot/3.5/appendix/dependency-versions/coordinates.html (3.5.16) — kotlin-stdlib, grpc, protobuf, netty, testcontainers 버전 (확인 2026-10-23)
- [S28] https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-4.1-Release-Notes — gRPC 지원 추가 (확인 2026-10-23)
- [S29] https://github.com/grpc/grpc-java/blob/master/README.md — "supports Java 8 and later", 1.85.0 (확인 2026-10-23)
- [S30] https://github.com/grpc/proposal/blob/master/P5-jdk-version-support.md — JDK 지원 정책 (확인 2026-10-23)
- [S31] https://github.com/grpc/grpc-java/releases — 1.83.0 Netty 4.2.15, 1.84.0 "Bump Context to JDK 8", 1.85.0 Netty 4.2.17, 1.85.1 (확인 2026-10-23)
- [S32] grpc-java master `netty/src/main/java/io/grpc/netty/UdsNettyChannelProvider.java`, `UdsNameResolverProvider.java`, `Utils.java` L112-135, L366-412, `NettyChannelBuilder.java` L93-95 — https://raw.githubusercontent.com/grpc/grpc-java/master/netty/src/main/java/io/grpc/netty/ (확인 2026-10-23)
- [S33] grpc-java master `netty/shaded/build.gradle` L21-60 — https://raw.githubusercontent.com/grpc/grpc-java/master/netty/shaded/build.gradle (확인 2026-10-23)
- [S34] https://grpc.github.io/grpc-java/javadoc/io/grpc/netty/NettyChannelBuilder.html — 1.85.0, `channelType(Class, Class<? extends SocketAddress>)`, `forAddress(SocketAddress)` (확인 2026-10-23)
- [S35] https://netty.io/news/2024/05/22/4-1-110-Final.html — NIO 전송 UDS 지원, Java 16+ (확인 2026-10-23)
- [S36] https://raw.githubusercontent.com/netty/netty/4.2/transport/src/main/java/io/netty/channel/socket/nio/NioDomainSocketChannel.java — L60-122, L300-325 (확인 2026-10-23)
- [S37] https://raw.githubusercontent.com/netty/netty/4.2/transport-classes-epoll/src/main/java/io/netty/channel/epoll/Epoll.java — L35-37 `io.netty.transport.noNative` (확인 2026-10-23)
- [S38] https://openjdk.org/jeps/380 — Unix-Domain Socket Channels, Release 16, Windows 10/Server 2019 (확인 2026-10-23)
- [S39] https://github.com/testcontainers/testcontainers-java/releases/tag/2.0.0 (확인 2026-10-23)
- [S40] https://java.testcontainers.org/supported_docker_environment/ , https://java.testcontainers.org/supported_docker_environment/windows/ (확인 2026-10-23)
- [S41] Maven Central JAR 직접 검사(클래스 파일 major 버전) — `org.testcontainers:testcontainers` 2.0.5/1.21.4, `io.grpc:grpc-core`/`grpc-api` 1.85.1, `com.google.protobuf:protobuf-java` 4.36.2, `org.openjdk.jmh:jmh-core` 1.37, https://repo1.maven.org/maven2/ (확인 2026-10-23)
- [S42] https://github.com/openjdk/jmh — README (확인 2026-10-23)
- [S43] https://github.com/melix/jmh-gradle-plugin , https://plugins.gradle.org/m2/me/champeau/jmh/me.champeau.jmh.gradle.plugin/maven-metadata.xml (0.7.3, 2025-01-30) (확인 2026-10-23)
- [S44] https://openjdk.org/jeps/444 — Virtual Threads, Release 21 (확인 2026-10-23)
- [S45] https://openjdk.org/jeps/491 — Synchronize Virtual Threads without Pinning, Release 24 (확인 2026-10-23)
- [S46] https://openjdk.org/jeps/472 — Prepare to Restrict the Use of JNI, Release 24 (확인 2026-10-23)
- [S47] https://openjdk.org/jeps/454 — Foreign Function & Memory API, Release 22 (확인 2026-10-23)
- [S48] https://docs.oracle.com/en/java/javase/25/core/restricted-methods.html — `--illegal-native-access` warn 기본, deny 예정 (확인 2026-10-23)
- [S49] https://ask.datomic.com/index.php/1883/datomic-jdk25-warning-restricted-method-java-system-called — Netty `NativeLibraryUtil` 경고 사례(2차 출처) (확인 2026-10-23)
- [S50] https://www.oracle.com/java/technologies/java-se-support-roadmap.html (확인 2026-10-23)
- [S51] https://adoptium.net/support/ — Temurin 릴리스 로드맵 (확인 2026-10-23)
- [S52] https://kotlinlang.org/docs/gradle-configure-project.html — stdlib 자동 의존성, 버전 정렬 (확인 2026-10-23)
- [S53] https://kotlinlang.org/docs/java-to-kotlin-interop.html — `@Throws`, `@JvmStatic`, `@JvmOverloads`, null 검사 (확인 2026-10-23)
- [S54] https://repo1.maven.org/maven2/org/jetbrains/kotlin/kotlin-stdlib/maven-metadata.xml , `kotlin-stdlib-2.4.21.jar`(Content-Length 1,853,315 B, Last-Modified 2026-10-08)와 `.pom`, `grpc-netty-shaded-1.85.1.jar` 크기 (확인 2026-10-23)
- [S55] https://docs.gradle.org/current/userguide/compatibility.html — Gradle 9.8.2 (확인 2026-10-23)
- [S56] https://docs.gradle.org/current/userguide/toolchains.html (확인 2026-10-23)
- [S57] https://maven.apache.org/docs/history.html — 3.9.16, 4.0.0-rc-7 (확인 2026-10-23)
- [S58] https://maven.apache.org/plugins/maven-compiler-plugin/examples/set-compiler-release.html (확인 2026-10-23)
- [S59] https://maven.apache.org/tools/wrapper/ — 3.3.4 (확인 2026-10-23)
- [S60] https://github.com/google/protobuf-gradle-plugin — 0.10.0, Gradle 7.6+/Java 11+ (확인 2026-10-23)
- [S61] https://ascopes.github.io/protobuf-maven-plugin/requirements.html , https://ascopes.github.io/protobuf-maven-plugin/using-protoc-plugins.html , https://repo1.maven.org/maven2/io/github/ascopes/protobuf-maven-plugin/maven-metadata.xml (5.1.11) (확인 2026-10-23)
- [S62] https://repo1.maven.org/maven2/org/xolstice/maven/plugins/protobuf-maven-plugin/maven-metadata.xml (0.6.1, 2018-10), https://repo1.maven.org/maven2/kr/motd/maven/os-maven-plugin/maven-metadata.xml (1.7.1, 2022-11) (확인 2026-10-23)
- [S63] 로컬 Temurin 25.0.4.1 `src.zip` → `java.desktop/javax/imageio/**` `@since` 집계(1.4/1.5/1.6/9만 존재), `spi/IIORegistry.java` L52-80, L101-130, L156-180, `spi/ServiceRegistry.java` L50-87, `ImageIO.java` L68-106, `spi/ImageReaderSpi.java` L76-84 (확인 2026-10-23)
- [S64] https://api.github.com/repos/openjdk/jdk/commits?path=src/java.desktop/share/classes/javax/imageio/spi/IIORegistry.java (및 `ImageIO.java`, `ServiceRegistry.java`) — 8344064, 8345143, 8359053, 8365292, 8375350 커밋 (확인 2026-10-23)
