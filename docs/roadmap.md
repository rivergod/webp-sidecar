# 로드맵

각 단계는 **완료 기준(Definition of Done)** 을 모두 만족해야 끝난다. 다음 단계의 착수 조건이 있으면 함께 적는다. 진행 상태가 바뀌면 이 문서를 고친다.

| 단계 | 내용 | 상태 |
|---|---|---|
| Phase 0 | 기반 정리 | 진행 중 — 라이선스 결정, `spikes/` 정리 남음 |
| Phase 1 | 서버: 정지 이미지 디코딩/인코딩 + 헬스체크 + Docker | 대기 |
| Phase 2 | JVM SPI 클라이언트(정지 이미지) + 통합 테스트 | 대기 |
| Phase 3 | 애니메이션 + ICC/EXIF/XMP | 대기 |
| Phase 4 | JMH 벤치마크 + 결과 보고서 | 대기 |
| Phase 5 | 보안 강화(seccomp, 퍼징) + proto 회고 | 대기 |

## Phase 0: 기반 정리

산출물: 저장소 골격, agent glue, 작업 규칙(conventions), 조사 문서(research 0001–0005), ADR 0001–0008 초안, gRPC API 설계와 proto 초안, 서버 언어 스파이크(`spikes/`), 이 로드맵.

완료 기준:

- [x] 위 산출물이 `docs/README.md` 인덱스에서 모두 연결된다.
- [x] 모든 ADR이 `proposed` 상태이고 선택지 비교와 추천안이 있다.
- [x] `buf lint`(STANDARD + COMMENTS)가 통과한다.
- [x] 사람이 ADR-0002(서버 언어), ADR-0003(전송), ADR-0004(픽셀 포맷), ADR-0005(JDK·언어·빌드 도구), ADR-0008(libwebp 버전 고정)을 `accepted` 또는 `rejected`로 결정한다. — 2026-10-23, ADR 0001–0008 모두 accepted.
- [ ] ADR-0002가 정해지면 `spikes/`를 삭제한다(결과는 research/0003에 남아 있다).
- [x] 서버 언어를 사람이 결정한다 — 2026-10-23 Rust. 팀 친숙도 질문의 개별 답은 기록되지 않았다([research/0003](research/0003-server-language-rust-vs-go.md) 8절).
- [ ] 라이선스를 정한다([LICENSE](../LICENSE)).

## Phase 1: 서버 — 정지 이미지 + 헬스체크 + Docker

착수 조건: ADR-0002, 0003, 0004, 0008 accepted.

범위: `GetInfo`, `GetFeatures`(정지 이미지, 메타데이터 제외), `Decode`(정지 이미지), `Encode`(정지 이미지, 메타데이터 제외), 표준 헬스체크, UDS·TCP 리슨, 상한 검사, 컨테이너 이미지.

완료 기준:

- [ ] libwebp 버전이 빌드에서 고정되고 `GetInfo`가 그 버전을 돌려준다.
- [ ] lossless 샘플의 디코딩 결과가 기준(같은 libwebp의 `dwebp` 출력)과 픽셀 단위로 일치한다.
- [ ] lossy 인코딩 왕복이 이름 붙은 PSNR 임계값 이상이다(측정 근거를 상수 주석에 기록).
- [ ] 4MB를 넘는 이미지(예: 3840×2160)가 청크 스트리밍으로 디코딩·인코딩된다.
- [ ] 손상/악성 입력(잘림, 크기 필드 조작, 거대 크기 선언)이 `INVALID_ARGUMENT` 또는 `RESOURCE_EXHAUSTED`로 거부되고 서버는 계속 응답한다.
- [ ] 상한을 서버 설정값으로 받는다(코드에 고정하지 않는다). 설정 방식과 키 이름, 기본값과 그 근거를 [grpc-api.md §3](design/grpc-api.md)에 적는다.
- [ ] `grpc.health.v1.Health/Check`가 `SERVING`을 돌려준다.
- [ ] Docker 이미지가 non-root, read-only 루트 파일시스템, 외부 네트워크 없음으로 실행되고, 공유 볼륨의 UDS로 호출된다.
- [ ] 서버 단위 테스트와 문서 주석 린터가 통과하고, 그 명령이 `AGENTS.md`에 적혀 있다.

## Phase 2: JVM SPI 클라이언트 — 정지 이미지

착수 조건: ADR-0005 accepted, Phase 1 완료.

완료 기준:

- [ ] 첫 작업: Java UDS PoC. JNI 없는 경로(JDK `UnixDomainSocketAddress` + Netty NIO)와 grpc-java 공식 경로(`unix:` + Netty native epoll)를 모두 테스트하고 동작 여부와 처리량을 기록한다(ADR-0003).
- [ ] `ImageIO.read` / `ImageIO.write`가 정지 WebP를 처리한다. SPI는 classpath(`META-INF/services`)와 module path(`module-info`의 `provides`) 양쪽에서 등록된다.
- [ ] `canDecodeInput`이 네트워크 없이 매직 바이트만 검사한다(단위 테스트, 손상 헤더 포함).
- [ ] 설정이 시스템 프로퍼티 → 환경변수 → 기본값 순서로 해석된다(단위 테스트).
- [ ] 오류 매핑: 서버 중지 → `UNAVAILABLE` 계열 메시지, 손상 입력 → `INVALID_ARGUMENT` 계열 메시지, 상한 초과 → 상한 메시지. 모두 cause에 원래 예외가 있다(통합 테스트).
- [ ] 클라이언트가 응답 헤더 크기를 힙 할당 전에 검증한다(단위 테스트).
- [ ] Testcontainers 통합 테스트가 TCP로 통과한다. UDS 통합 테스트는 지원 플랫폼에서 통과한다(ADR-0003).
- [ ] 손상 입력으로 서버 프로세스를 죽여도 JVM은 살아 있고 `IIOException`을 받는다(ADR-0001 확인 방법).
- [ ] RGBA8 → `BufferedImage` 변환 비용을 측정해 기록한다(ADR-0004 확인 방법).

## Phase 3: 애니메이션 + ICC/EXIF/XMP

완료 기준:

- [ ] `getNumImages(true)`가 프레임 수를, `read(i)`가 합성된 i번째 프레임을 돌려준다. 결과가 libwebp `WebPAnimDecoder` 기준 출력과 일치한다.
- [ ] 순차 읽기 정책(`DecodeAnimation` 사용 방식)을 정하고 문서화한다([grpc-api.md §2.4](design/grpc-api.md)).
- [ ] 시퀀스 쓰기(`writeToSequence`)로 만든 애니메이션을 다시 읽으면 프레임 픽셀(lossless), 표시 시간, 반복 횟수가 일치한다.
- [ ] ICC/EXIF/XMP 읽기·쓰기 왕복에서 페이로드 바이트가 일치한다.
- [ ] `IIOMetadata` 표준 포맷(`javax_imageio_1.0`)이 TwelveMonkeys와 같은 노드를 내고, ICC/EXIF/XMP·애니메이션 정보를 담는 native 포맷을 정의해 문서화한다(TwelveMonkeys에는 native 포맷이 없다 — [research/0001](research/0001-java-webp-landscape.md)).
- [ ] 애니메이션 악성 입력(프레임 수 초과, 캔버스 밖 프레임) 테스트가 통과한다.

## Phase 4: JMH 벤치마크 + 결과 보고서

완료 기준:

- [ ] 같은 샘플로 이 플러그인(UDS, TCP), TwelveMonkeys, darkxanter, libwebp Wasm + Chicory(ADR-0001의 경쟁 대안)를 비교한다. JDeli는 트라이얼 jar가 있을 때만 포함한다.
- [ ] libwebp를 Wasm으로 빌드해 Chicory로 실행하는 비교 구현을 `bench/`에 둔다(벤치 전용, 제품 코드 아님). 사용한 libwebp 버전과 Wasm 빌드 옵션(SIMD, 스레드)을 기록한다.
- [ ] 이미지 크기별(작음/중간/4K 이상), 종류별(lossy/lossless/alpha/애니메이션)로 측정한다.
- [ ] 벤치마크를 한 명령으로 재현할 수 있고, 환경(CPU, OS, JDK, libwebp 버전, JMH 설정)이 결과와 함께 기록된다.
- [ ] 결과 보고서를 `docs/research/`에 쓰고, 벤더 공개 수치([research/0001](research/0001-java-webp-landscape.md))와 비교한다.
- [ ] 청크 크기, 전송 방식(UDS/TCP), 픽셀 변환 비용에 대한 결론을 ADR-0003/0004의 확인 방법에 맞춰 적는다.

## Phase 5: 보안 강화 + proto 회고

완료 기준:

- [ ] 서버 seccomp 프로파일을 만들고, 적용 상태에서 전체 통합 테스트가 통과한다.
- [ ] 서버 입력 경로(Decode, GetFeatures, DecodeAnimation) 퍼저를 만들고 실행 결과(시간, 발견 사항)를 기록한다.
- [ ] 컨테이너 제한(메모리/CPU, read-only, 네트워크 차단, non-root)이 실제로 적용됐는지 검증한다.
- [ ] proto 회고 ADR(계속/중단 판단과 근거)을 `proposed`로 작성한다.
