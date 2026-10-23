# client-jvm

ImageIO `ImageReaderSpi` / `ImageWriterSpi` 구현체(gRPC 클라이언트). `ImageIO.read` / `ImageIO.write`로 투명하게 동작하는 것이 목표다.

- Java, JDK 17 기준(`--release 17`), Gradle(Kotlin DSL)([ADR-0005](../docs/adr/0005-jdk-language-build.md)). 테스트는 17 / 21 / 25 / 27 toolchain에서 돌린다.
- JNI와 네이티브 라이브러리를 쓰지 않는다. UDS는 JNI 없는 경로를 우선한다([ADR-0003](../docs/adr/0003-transport.md)).
- Maven 좌표 `net.rivergod:webp-imageio-external-server-proto`([ADR-0010](../docs/adr/0010-maven-coordinates.md)). 패키지·모듈 이름 `net.rivergod.webp.imageio`, 디코딩 결과 타입, 쓰기 파라미터는 [ADR-0013](../docs/adr/0013-client-package-and-imageio-mapping.md), 애니메이션·메타데이터는 [ADR-0014](../docs/adr/0014-animation-and-metadata-mapping.md).
- 코드는 Phase 2에서 추가한다. 첫 작업은 UDS PoC다([roadmap](../docs/roadmap.md)).

## 구현 기본값 (Phase 2)

바꾸려면 이 목록을 고치고 이유를 커밋 메시지에 적는다. 설계를 바꾸는 수준이면 ADR을 쓴다.

- Gradle 루트는 저장소 루트(`settings.gradle.kts`)이고 하위 프로젝트는 `client-jvm`, `bench`다. Gradle wrapper를 커밋한다.
- 클라이언트 설정 키는 [grpc-api.md §5](../docs/design/grpc-api.md) 그대로다(데드라인 기본 30 s).
- gRPC 채널: 처음 쓸 때 만드는 공유 채널 하나, 데몬 스레드. `onDeregistration`에 정리를 맡기지 않는다.
- 통합 테스트 서버: Testcontainers가 `deploy/`의 Dockerfile로 직접 빌드한다.
