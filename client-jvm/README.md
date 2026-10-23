# client-jvm

ImageIO `ImageReaderSpi` / `ImageWriterSpi` 구현체(gRPC 클라이언트). `ImageIO.read` / `ImageIO.write`로 투명하게 동작하는 것이 목표다.

- JDK 최소 버전, 구현 언어(Java/Kotlin), 빌드 도구는 [ADR-0005](../docs/adr/0005-jdk-language-build.md)에서 결정한다. 결정 전에는 코드를 두지 않는다.
