# client-jvm

ImageIO `ImageReaderSpi` / `ImageWriterSpi` 구현체(gRPC 클라이언트). `ImageIO.read` / `ImageIO.write`로 투명하게 동작하는 것이 목표다.

- Java, JDK 17 기준(`--release 17`), Gradle(Kotlin DSL)([ADR-0005](../docs/adr/0005-jdk-language-build.md)). 테스트는 17 / 21 / 25 / 27 toolchain에서 돌린다.
- JNI와 네이티브 라이브러리를 쓰지 않는다. UDS는 JNI 없는 경로를 우선한다([ADR-0003](../docs/adr/0003-transport.md)).
- 코드는 Phase 2에서 추가한다. 첫 작업은 UDS PoC다([roadmap](../docs/roadmap.md)).
