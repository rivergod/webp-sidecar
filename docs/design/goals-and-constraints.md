# 목표, 비목표, 핵심 설계 제약

이 문서는 프로젝트 범위와 설계 전반에 걸친 제약을 정리한다. 개별 결정의 선택지 비교와 근거는 `docs/adr/`에 있다.

## 1. 목표

- libwebp를 **직접 링크한** gRPC 서버. 요청마다 CLI(`cwebp`/`dwebp`)를 실행하는 방식은 쓰지 않는다.
- ImageIO `ImageReaderSpi` / `ImageWriterSpi` 구현체(gRPC 클라이언트). `ImageIO.read` / `ImageIO.write`로 투명하게 동작한다.
- 지원 범위
  - lossy / lossless / alpha 읽기·쓰기
  - 애니메이션(합성된 프레임) 읽기·쓰기
  - ICC / EXIF / XMP 읽기·쓰기
- JMH 벤치마크: TwelveMonkeys imageio-webp, darkxanter webp-imageio와 같은 샘플로 비교한다. JDeli는 트라이얼 jar가 있을 때만 선택적으로 포함한다.

## 2. 비목표 (proto 단계)

- 원격 다중 테넌트 서비스, 인증/인가, 수평 확장
- WebP 외 다른 포맷
- Android 지원

## 3. 핵심 설계 제약

| 주제 | 제약 | 관련 |
|---|---|---|
| 전송량 | 디코딩 결과와 인코딩 입력은 압축 해제된 RGBA라서 크다(3840×2160×4 ≈ 33MB). gRPC 기본 메시지 한도 4MB를 넘으므로 **청크 스트리밍**을 기본으로 한다. | ADR-0003, ADR-0004 |
| 배치 | 같은 호스트/파드의 **사이드카 + Unix Domain Socket**을 기본 전송으로 하고, TCP는 옵션으로 둔다. | ADR-0001, ADR-0003 |
| `canDecodeInput` | RIFF/WEBP 매직 바이트만 로컬에서 검사한다. 네트워크 호출은 금지한다. | [grpc-api.md](grpc-api.md) |
| SPI 설정 | SPI는 인자 없는 생성자로 전역 등록되므로, 엔드포인트와 타임아웃은 **시스템 프로퍼티 → 환경변수 → 기본값** 순서로 해석한다. | ADR-0005 |
| 오류 매핑 | 서버 불가(`UNAVAILABLE`, `DEADLINE_EXCEEDED`)와 입력 손상(`INVALID_ARGUMENT`)을 구분해 `IIOException`의 메시지와 원인(cause)으로 전달한다. | [grpc-api.md](grpc-api.md) |
| Decompression bomb | 서버는 디코딩 전에 `WebPGetFeatures`(애니메이션은 demux 결과)로 크기를 검사해 상한을 넘으면 거부한다. 클라이언트도 응답 헤더의 크기를 보고 힙 할당 전에 검증한다. | [grpc-api.md](grpc-api.md) |
| 애니메이션 | `getNumImages()`는 프레임 수를, `read(i)`는 합성된 i번째 프레임을 돌려준다. 순차 읽기 효율을 위해 프레임 스트리밍 RPC를 따로 둔다. | [grpc-api.md](grpc-api.md) |
| 메타데이터 | `IIOMetadata` 트리 구조는 가능하면 TwelveMonkeys 형식과 호환되게 한다(교체 용이성). 단, TwelveMonkeys는 표준 포맷(`javax_imageio_1.0`)의 압축·chroma 정보만 노출하고 native 포맷, ICC/EXIF/XMP, 애니메이션 정보는 노출하지 않는다([research/0001](../research/0001-java-webp-landscape.md)). 따라서 호환 대상은 표준 포맷 부분이고, 나머지는 이 프로젝트가 native 포맷을 새로 정의해야 한다. | Phase 3 |
| 격리 강화 | 서버 컨테이너에 메모리/CPU 제한, read-only FS, 외부 네트워크 차단, non-root, (가능하면) seccomp 프로파일을 적용한다. | ADR-0001, Phase 5 |
| libwebp 버전 | 서버 빌드에서 명시적으로 고정하고 최신 안정판을 쓴다. 버전은 `GetInfo` RPC로 노출한다. 최신 릴리스(1.6.0) 이후 main에 미릴리스 수정이 쌓여 있어 고정 방식을 따로 정한다. | ADR-0002, ADR-0008 |
