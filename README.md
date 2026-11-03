# webp-sidecar

libwebp를 **별도 프로세스(gRPC 사이드카 서버)** 로 격리하고, JVM에서는 그 서버를 호출하는 **ImageIO SPI 플러그인**으로 WebP를 읽고 쓰는 구조를 검증하는 프로토타입이다.

## 가설

JNI 없이 다음 세 가지를 동시에 얻을 수 있다.

1. 네이티브(libwebp) 수준의 속도
2. 크래시와 취약점의 격리 — libwebp의 메모리 버그가 JVM 프로세스로 번지지 않는다.
3. libwebp 상위 API(`WebPAnimDecoder` / `WebPAnimEncoder` / `WebPDemux` / `WebPMux`)를 통한 애니메이션 합성과 ICC/EXIF/XMP 읽기·쓰기

목적은 가설 검증이다. 제품화 여부는 검증 결과를 보고 판단한다. 배경 조사는 [docs/research/0001-java-webp-landscape.md](docs/research/0001-java-webp-landscape.md), 목표와 설계 제약은 [docs/design/goals-and-constraints.md](docs/design/goals-and-constraints.md)에 있다.

## 상태

Phase 0(기반 정리). 단계별 계획과 완료 기준은 [docs/roadmap.md](docs/roadmap.md)에 있다.

## 디렉터리

| 경로 | 내용 |
|---|---|
| `proto/` | gRPC/Protobuf 정의 (`webp/v1`) |
| `server/` | libwebp를 링크한 gRPC 서버 (언어: ADR-0002에서 결정) |
| `client-jvm/` | ImageIO `ImageReaderSpi`/`ImageWriterSpi` 플러그인 (JDK·언어: ADR-0005에서 결정) |
| `bench/` | JMH 벤치마크 |
| `samples/` | 테스트 이미지와 출처·라이선스 목록 |
| `deploy/` | Dockerfile, compose, 보안 프로파일 |
| `spikes/` | 결정을 위한 버릴 실험 코드 |
| `docs/` | 조사, ADR, 설계, 작업 규칙 ([docs/README.md](docs/README.md)) |

## 작업 규칙

사람과 AI 에이전트 모두 [AGENTS.md](AGENTS.md)를 따른다. 상세 규칙은 [docs/conventions/](docs/conventions/)에 있다.

## 라이선스

Apache License 2.0. [LICENSE](LICENSE) 참고. 제3자 내용은 각자의 라이선스를 따른다(예: [docs/conventions/working-principles.md](docs/conventions/working-principles.md)의 발췌는 MIT).
