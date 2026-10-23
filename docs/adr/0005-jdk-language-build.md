# 0005. 클라이언트: JDK 17 기준(`--release 17`), Java, Gradle

- 상태: proposed
- 날짜: 2026-10-23
- 결정자: -
- 관련 문서: [docs/research/0004-jdk-baseline.md](../research/0004-jdk-baseline.md), [ADR-0003](0003-transport.md)

## 맥락

`client-jvm/`(ImageIO SPI 플러그인)과 `bench/`(JMH)의 기반을 정해야 한다.

1. 최소 JDK 버전
2. 구현 언어(Java / Kotlin)
3. 빌드 도구(Gradle / Maven)

조사 결과 요약([research/0004](../research/0004-jdk-baseline.md), 확인 2026-10-23):

- ImageIO의 마지막 구조 변화는 JDK 9다(`java.desktop` 모듈, `module-info`의 `provides`, JEP 262 TIFF). JDK 10~27에는 `javax.imageio` 공개 API 추가가 없다. 따라서 후보는 9 이후 LTS인 11 / 17 / 21 / 25다.
- 2026-10-23 기준 OSS 지원 중인 Spring Boot는 4.0.x, 4.1.x이고 둘 다 최소 Java 17이다. Boot 3.x의 OSS 지원은 끝났다(3.5는 2026-06-30). 지원 중인 모든 Spring 라인(Boot 3.5/4.x 상용 포함, Framework 6.2/7.x)이 Java 17 이상을 요구한다.
- grpc-java는 Java 8 이상을 지원한다. JNI 없는 UDS 경로(JDK `UnixDomainSocketAddress` + Netty NIO)는 JDK 16+가 필요하다(ADR-0003).
- JDK 26에서 ImageIO 스트림과 레지스트리의 `finalize()`가 제거돼, 레지스트리가 GC될 때 `onDeregistration`이 호출되지 않는다.
- Spring Boot 4.1은 grpc-java 1.84.1과 protobuf-java 4.36.2를 의존성 관리에 포함한다. 사용자 앱에서 이 버전이 플러그인의 gRPC 버전을 덮어쓸 수 있다.

## 결정 동인

1. 주요 사용처(Spring 애플리케이션)가 지원하는 가장 낮은 JDK보다 높게 잡지 않는다.
2. JNI 없는 UDS가 가능해야 한다(ADR-0001, ADR-0003).
3. 플러그인 사용자에게 전이되는 런타임 의존성이 적어야 한다.
4. Windows에서도 개발·빌드·테스트가 쉬워야 한다.
5. protobuf/gRPC 코드 생성과 JMH를 무리 없이 다뤄야 한다.

## 검토한 선택지

- 최소 JDK: 11 / **17** / 21 / 25
- 언어: **Java** / Kotlin
- 빌드 도구: **Gradle(Kotlin DSL)** / Maven

## 결정

**JDK 17(`--release 17`), Java, Gradle(Kotlin DSL)을 추천한다.** Gradle 추천은 약하다(아래 Maven 대안 참고).

- 컴파일은 `--release 17`로 하고, 빌드는 JDK 25 toolchain으로 한다. 테스트는 17 / 21 / 25 / 27에서 돌린다(toolchain 매트릭스).
- SPI는 classpath용 `META-INF/services`와 module path용 `module-info`의 `provides`를 둘 다 둔다.
- gRPC 채널 같은 자원 정리를 `onDeregistration`에 맡기지 않는다(JDK 26 변경).
- 가상 스레드는 요구하지 않는다. 다만 긴 I/O를 `synchronized`로 감싸지 않고 thread local 풀링을 피해서 21+ 가상 스레드 환경과 호환되게 한다.
- gRPC/protobuf 의존성은 shading하지 않는다. Spring Boot가 관리하는 버전(4.0.x, 4.1.x)과의 호환을 통합 테스트로 확인한다.
- Maven groupId와 Java 패키지 이름은 사람이 정한다(열린 항목).

### 결과

- 긍정: Spring Boot 4.x와 3.5 상용 지원 사용자를 모두 포함한다. JNI 없는 UDS 경로를 쓸 수 있는 가장 낮은 LTS다.
- 긍정: Java로 쓰면 `kotlin-stdlib`(약 1.8MB)과 Kotlin 버전 충돌(Boot 3.5: 1.9.25, 4.0: 2.2.21, 4.1: 2.3.21)이 사용자에게 전이되지 않는다.
- 긍정: Gradle toolchain이 JDK를 자동으로 받아 Windows에서도 다중 JDK 테스트가 쉽다. Google 공식 protobuf Gradle 플러그인이 있다.
- 부정: 17에서는 21+의 언어 기능(record 패턴, sequenced collections 등)을 쓸 수 없다. Temurin 17 지원은 최소 2027-10까지이므로 2027년 상반기에 21로 올릴지 다시 본다.
- 부정: JMH가 공식 지원하는 빌드 도구는 Maven뿐이고, Gradle JMH 플러그인(`me.champeau.jmh`)의 Gradle 9 호환은 미확인이다. Phase 4의 리스크다.
- 부정: 사용자 앱의 grpc-java 버전이 다를 수 있다(shading하지 않기 때문).

### 확인 방법

- Phase 2: 17 / 21 / 25 / 27 toolchain 매트릭스에서 단위·통합 테스트 통과.
- Phase 2: Spring Boot 4.1 관리 버전(grpc-java 1.84.1, protobuf-java 4.36.2)으로 플러그인을 쓰는 통합 테스트 통과.
- Phase 4 시작 시: Gradle JMH 플러그인이 현재 Gradle에서 동작하는지 확인. 안 되면 `bench/`만 별도 구성(직접 source set 또는 Maven 모듈)하는 것을 새 ADR로 제안한다.

## 선택지별 장단점

### 최소 JDK

| 후보 | 장점 | 단점 |
|---|---|---|
| 11 | 가장 넓은 호환 | JNI 없는 UDS 불가(JDK 16+ 필요). 지원 중인 Spring 라인 중 11을 쓰는 것이 없다. |
| **17** | Spring 최저 지원과 일치, JNI 없는 UDS 가능 | 최신 언어 기능 일부 사용 불가 |
| 21 | 가상 스레드, 최신 언어 기능 | Spring 17 사용자 배제 |
| 25 | 가장 최신 LTS | Spring 최저 지원보다 높다 |

### 언어

- Java: 전이 의존성 없음, ImageIO/SPI API가 Java 관례 그대로(checked `IOException`).
- Kotlin: 코드가 간결하다. 대신 `kotlin-stdlib`이 전이되고, Java 호출자가 `IOException`을 잡으려면 `@Throws`를 빠짐없이 붙여야 한다.

### 빌드 도구

- Gradle(Kotlin DSL): toolchain 자동 다운로드, 공식 protobuf 플러그인, 멀티 모듈. JMH는 커뮤니티 플러그인.
- Maven: JMH 공식 지원, `io.github.ascopes:protobuf-maven-plugin`이 OS별 protoc를 자동 관리, 선언적. toolchain JDK 자동 다운로드는 미확인.
- 플러그인 사용자는 어느 도구로 빌드했는지와 무관하므로 개발 편의로 고른다. 차이가 크지 않아 사람의 선호가 있으면 그쪽을 따른다.

## 참고

- 모든 사실의 출처: [docs/research/0004-jdk-baseline.md](../research/0004-jdk-baseline.md)
