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
- 통합 테스트 서버: Gradle `buildServerImage` 작업이 `deploy/Dockerfile`을 docker CLI로 빌드하고, Testcontainers가 그 이미지를 띄운다. (원래 기본값은 Testcontainers가 직접 빌드하는 것이었지만, 저장소 루트를 컨텍스트로 넘기면 `.dockerignore`가 적용되지 않아 `server/target` 등이 전송되므로 바꿨다.)

## 명령

| 작업 | 명령(저장소 루트) |
|---|---|
| 단위 테스트 | `./gradlew :client-jvm:test` |
| 공개 API 문서(javadoc, 생성된 proto 코드 제외) | `./gradlew :client-jvm:javadoc` → `client-jvm/build/docs/javadoc` |
| 통합 테스트(Docker 필요) | `./gradlew :client-jvm:integrationTest` |
| JDK 17/21/25/27 매트릭스 | `./gradlew :client-jvm:testMatrix` |
| Spring Boot 4.1 관리 버전(grpc-java 1.84.1)으로 | `./gradlew :client-jvm:test :client-jvm:integrationTest -PbootCompat` |
| UDS PoC / native access 거부 PoC | `./gradlew :client-jvm:udsPoc`, `./gradlew :client-jvm:udsPocDeny` |

## 사용

```java
// 서버가 /run/webp-imageio/webp.sock에서 기다린다고 가정(기본값). 다른 곳이면 -Dwebp.imageio.endpoint=...
BufferedImage image = ImageIO.read(new File("photo.webp"));
ImageIO.write(image, "webp", new File("out.webp"));   // 기본: lossy, libwebp 기본 품질
```

- 무손실 쓰기: `ImageWriteParam`에서 `setCompressionMode(MODE_EXPLICIT)`, `setCompressionType("Lossless")`.
- 애니메이션 쓰기: `prepareWriteSequence` → `writeToSequence` × N → `endWriteSequence`. 프레임 표시 시간은 native 이미지 메타데이터 `Frame@durationMs`로 준다([design/imageio-metadata.md](../docs/design/imageio-metadata.md)). 모든 프레임은 캔버스 전체 크기의 **완성된 화면**이어야 한다.
- GIF → WebP 변환: JDK GIF 리더의 `read(i)`는 프레임 원본(프레임 사각형 크기, 바뀌지 않은 픽셀은 투명)을 돌려준다. 그대로 넘기면 결과가 깨진다. disposal을 적용해 캔버스에 합성한 뒤 넘긴다. 참고 구현: [bench/.../GifFrames.java](../bench/src/main/java/net/rivergod/webp/imageio/bench/GifFrames.java). 영상 같은 GIF는 lossy가 GIF의 0.14–0.35배 크기다. lossless는 GIF보다 커질 수 있다([research/0012](../docs/research/0012-gif-conversion-load.md)).
- 서버가 없거나 응답하지 않으면 `IIOException("WebP server unavailable …")`, 손상 입력은 `IIOException("Invalid or corrupted WebP data …")`, 상한 초과는 `IIOException("WebP image exceeds … limit …")`. 원래 gRPC 예외는 cause에 있다.
- 서버가 혼잡해 대기 상한을 넘으면 `IIOException("WebP server busy …")`가 난다([ADR-0017](../docs/adr/0017-output-volume-limits.md)). 기본으로는 플러그인이 다시 시도하지 않는다. `busyRetries`(아래 설정)를 켜면 플러그인이 물러섰다가 다시 시도한다. 서버 쪽 요청 시간 상한(기본 60초)을 넘으면 `IIOException("WebP server request time limit exceeded …")`가 난다.

## 설정

항목마다 아래 순서로 처음 정해진 값을 쓴다([ADR-0020](../docs/adr/0020-imageio-config-injection.md), [design/grpc-api.md §5](../docs/design/grpc-api.md)).

1. 리더·writer 단위: `spi.createReaderInstance(config)`
2. 등록 단위: `new WebPImageReaderSpi(config)`를 수동 등록
3. 시스템 프로퍼티 `webp.imageio.*`
4. 환경변수 `WEBP_IMAGEIO_*`
5. 기본값

| 항목 | 시스템 프로퍼티 | 기본값 |
|---|---|---|
| `endpoint` | `webp.imageio.endpoint` | `unix:///run/webp-imageio/webp.sock` |
| `deadlineMs` | `webp.imageio.deadline-ms` | 30000. 혼잡 재시도 포함 |
| `maxPixels` | `webp.imageio.max-pixels` | 0(서버 상한만) |
| `busyRetries` | `webp.imageio.busy-retries` | 0(끔). 최대 10 |

```java
// 등록 단위: JVM의 ImageIO.read 전체가 이 설정을 쓴다. 같은 클래스의 자동 등록 SPI를 대체한다(JVM에 하나).
WebPClientConfig config = WebPClientConfig.builder()
        .endpoint("unix:///var/run/webp/webp.sock")
        .busyRetries(3)
        .build();
IIORegistry.getDefaultInstance().registerServiceProvider(new WebPImageReaderSpi(config));
IIORegistry.getDefaultInstance().registerServiceProvider(new WebPImageWriterSpi(config));

// 리더 단위: 서버를 여러 개 쓸 때(예: 테넌트별 서버). 비운 항목은 SPI 설정에서 채운다.
ImageReader reader = new WebPImageReaderSpi()
        .createReaderInstance(WebPClientConfig.builder().endpoint("tenant-b:50051").build());

// 호출 단위: 이 읽기만 데드라인 5초.
WebPReadParam param = (WebPReadParam) reader.getDefaultReadParam();
param.setDeadlineMs(5_000);
BufferedImage image = reader.read(0, param);

// 쓰기: 표준 압축 항목 + libwebp method(0–6).
WebPWriteParam wp = (WebPWriteParam) writer.getDefaultWriteParam();
wp.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
wp.setCompressionType(WebPWriteParam.LOSSLESS);
wp.setMethod(6);
```

- 혼잡 재시도는 서버가 일을 시작하기 전에 거부한 "server busy"만 다시 보낸다. 대상은 읽기(GetFeatures, Decode, 애니메이션 순차 읽기 시작)와 정지 이미지 쓰기다. 물러섬은 100 ms에서 두 배씩, 최대 1.6초이고, 전체가 데드라인 안이다. 애니메이션 쓰기(시퀀스)는 프레임을 받는 대로 보내므로 다시 시도하지 않는다. 앱이 시퀀스 전체를 다시 쓴다.
