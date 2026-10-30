# 로드맵

각 단계는 **완료 기준(Definition of Done)** 을 모두 만족해야 끝난다. 다음 단계의 착수 조건이 있으면 함께 적는다. 진행 상태가 바뀌면 이 문서를 고친다.

| 단계 | 내용 | 상태 |
|---|---|---|
| Phase 0 | 기반 정리 | 완료 (`spikes/` 삭제는 보류) |
| Phase 1 | 서버: 정지 이미지 디코딩/인코딩 + 헬스체크 + Docker | 완료 (2026-10-23) |
| Phase 2 | JVM SPI 클라이언트(정지 이미지) + 통합 테스트 | 완료 (2026-10-23) |
| Phase 3 | 애니메이션 + ICC/EXIF/XMP | 완료 (2026-10-23) |
| Phase 4 | JMH 벤치마크 + 결과 보고서 | 완료 (2026-10-23, JDeli는 요청 시) |
| Phase 5 | 보안 강화(seccomp, 퍼징) + 성능 보강 + proto 회고 | 완료 (2026-10-29). 사이드카 구조로 확정(ADR-0019), 버퍼 풀 확정(ADR-0018), 작업량 상한 확정·구현(ADR-0017), 메모리 예산 확정·구현(ADR-0016), 설정 주입 확정(ADR-0020, 2026-10-30) |

## Phase 0: 기반 정리

산출물: 저장소 골격, agent glue, 작업 규칙(conventions), 조사 문서(research 0001–0005), ADR 0001–0008 초안, gRPC API 설계와 proto 초안, 서버 언어 스파이크(`spikes/`), 이 로드맵.

완료 기준:

- [x] 위 산출물이 `docs/README.md` 인덱스에서 모두 연결된다.
- [x] 모든 ADR이 `proposed` 상태이고 선택지 비교와 추천안이 있다.
- [x] `buf lint`(STANDARD + COMMENTS)가 통과한다.
- [x] 사람이 ADR-0002(서버 언어), ADR-0003(전송), ADR-0004(픽셀 포맷), ADR-0005(JDK·언어·빌드 도구), ADR-0008(libwebp 버전 고정)을 `accepted` 또는 `rejected`로 결정한다. — 2026-10-23, ADR 0001–0008 모두 accepted.
- [ ] ADR-0002가 정해지면 `spikes/`를 삭제한다(결과는 research/0003에 남아 있다).
- [x] 서버 언어를 사람이 결정한다 — 2026-10-23 Rust. 팀 친숙도 질문의 개별 답은 기록되지 않았다([research/0003](research/0003-server-language-rust-vs-go.md) 8절).
- [x] 라이선스를 정한다 — 2026-10-23 Apache-2.0([ADR-0009](adr/0009-license.md)). 저작권자 표기와 `NOTICE`는 첫 배포 때 정한다.

## Phase 1: 서버 — 정지 이미지 + 헬스체크 + Docker

착수 조건: ADR-0002, 0003, 0004, 0008 accepted.

범위: `GetInfo`, `GetFeatures`(정지 이미지, 메타데이터 제외), `Decode`(정지 이미지), `Encode`(정지 이미지, 메타데이터 제외), 표준 헬스체크, UDS·TCP 리슨, 상한 검사, 컨테이너 이미지.

완료 기준:

- [x] libwebp 버전이 빌드에서 고정되고 `GetInfo`가 그 버전을 돌려준다. — libwebp-sys 0.14.4(v1.6.0 벤더), `GetInfo` 1.6.0 (server/tests/grpc.rs)
- [x] lossless 샘플의 디코딩 결과가 기준(같은 libwebp의 `dwebp` 출력)과 픽셀 단위로 일치한다. — 공식 벡터 `.pam` md5 96/96, 생성 샘플 RGBA8 SHA-256 전부 일치 (server/tests/conformance.rs, samples.rs)
- [x] lossy 인코딩 왕복이 이름 붙은 PSNR 임계값 이상이다(측정 근거를 상수 주석에 기록). — q90 실측 41.0 dB, 기준 40.0 dB
- [x] 4MB를 넘는 이미지(예: 3840×2160)가 청크 스트리밍으로 디코딩·인코딩된다. — 3840×2160 lossless 왕복(프로세스 안, 컨테이너 스모크)
- [x] 손상/악성 입력(잘림, 크기 필드 조작, 거대 크기 선언)이 `INVALID_ARGUMENT` 또는 `RESOURCE_EXHAUSTED`로 거부되고 서버는 계속 응답한다. — 손상 정지 이미지 48개가 libwebp 판정과 같다(samples.rs). 애니메이션 손상 입력은 Phase 3
- [x] 상한과 소켓 설정을 [ADR-0012](adr/0012-server-runtime-config.md)의 방식(명령줄 인자 + 환경변수, 키는 [grpc-api.md §3.1](design/grpc-api.md))으로 받는다. 코드에 고정하지 않는다. 기본값의 근거를 grpc-api.md §3에 적는다. — config.rs 단위 테스트, 키 표 grpc-api.md §3.1
- [x] 기본값 조합(메모리 1 GiB, CPU 2, 동시 2)에서 상한 직전 크기의 정지 이미지·애니메이션 디코딩과 인코딩을 동시에 돌려도 OOM이 나지 않는다. 나면 기본값 조정을 새 ADR로 제안한다(ADR-0012의 메모리 예산 위험). — OOM 확인됨(인코딩). 조정안 [ADR-0016](adr/0016-memory-budget-admission.md) accepted·구현(2026-10-29, 재측정 OOM 0건), 측정 [research/0007](research/0007-server-memory-budget.md). 애니메이션 측정은 Phase 3
- [x] `grpc.health.v1.Health/Check`가 `SERVING`을 돌려준다.
- [x] Docker 이미지가 non-root, read-only 루트 파일시스템, 외부 네트워크 없음으로 실행되고, 공유 볼륨의 UDS로 호출된다. — server/tests/container.rs, deploy/README.md
- [x] 서버 단위 테스트와 문서 주석 린터가 통과하고, 그 명령이 `AGENTS.md`에 적혀 있다.

## Phase 2: JVM SPI 클라이언트 — 정지 이미지

착수 조건: ADR-0005 accepted, Phase 1 완료.

완료 기준:

- [x] 첫 작업: Java UDS PoC. JNI 없는 경로(JDK `UnixDomainSocketAddress` + Netty NIO)와 grpc-java 공식 경로(`unix:` + Netty native epoll)를 모두 테스트하고 동작 여부와 처리량을 기록한다(ADR-0003). — [research/0008](research/0008-java-uds-poc.md)
- [x] `ImageIO.read` / `ImageIO.write`가 정지 WebP를 처리한다. SPI는 classpath(`META-INF/services`)와 module path(`module-info`의 `provides`) 양쪽에서 등록된다. — module path는 `ModuleLayer` + `ServiceLoader`로 확인(SpiRegistrationTest)
- [x] `canDecodeInput`이 네트워크 없이 매직 바이트만 검사한다(단위 테스트, 손상 헤더 포함).
- [x] 설정이 시스템 프로퍼티 → 환경변수 → 기본값 순서로 해석된다(단위 테스트).
- [x] 오류 매핑: 서버 중지 → `UNAVAILABLE` 계열 메시지, 손상 입력 → `INVALID_ARGUMENT` 계열 메시지, 상한 초과 → 상한 메시지. 모두 cause에 원래 예외가 있다(통합 테스트).
- [x] 클라이언트가 응답 헤더 크기를 힙 할당 전에 검증한다(단위 테스트). — 통합 테스트에서 할당 전 검사 누락(OOM)을 찾아 고쳤다(GetFeatures 크기로 먼저 검사)
- [x] Testcontainers 통합 테스트가 TCP로 통과한다. UDS 통합 테스트는 지원 플랫폼에서 통과한다(ADR-0003). — TCP·UDS 모두, JDK 17/21/25/27, Spring Boot 4.1 관리 버전(grpc-java 1.84.1)
- [x] 손상 입력으로 서버 프로세스를 죽여도 JVM은 살아 있고 `IIOException`을 받는다(ADR-0001 확인 방법). — libwebp 1.6.0을 죽이는 입력이 없어 요청 처리 중 SIGKILL로 모사했다
- [x] RGBA8 → `BufferedImage` 변환 비용을 측정해 기록한다(ADR-0004 확인 방법). — 4K 9.3–9.9 ms(research/0008)

## Phase 3: 애니메이션 + ICC/EXIF/XMP

완료 기준:

- [x] `getNumImages(true)`가 프레임 수를, `read(i)`가 합성된 i번째 프레임을 돌려준다. 결과가 libwebp `WebPAnimDecoder` 기준 출력과 일치한다. — 생성 애니메이션 12개(+메타 샘플)의 프레임별 해시가 manifest(WebPAnimDecoder 기준)와 일치
- [x] [ADR-0014](adr/0014-animation-and-metadata-mapping.md)의 순차 읽기 정책을 구현한다. `read(0..n-1)` 순차 읽기에서 `DecodeAnimation`이 한 번만 호출된다. — 파일마다 스트림 1회 확인(AnimationMetadataIntegrationTest)
- [x] 시퀀스 쓰기(`writeToSequence`)로 만든 애니메이션을 다시 읽으면 프레임 픽셀(lossless), 표시 시간, 반복 횟수가 일치한다.
- [x] ICC/EXIF/XMP 읽기·쓰기 왕복에서 페이로드 바이트가 일치한다.
- [x] `IIOMetadata` 표준 포맷(`javax_imageio_1.0`)이 TwelveMonkeys와 같은 노드를 낸다. native 포맷 `net_rivergod_webp_imageio_1.0`([ADR-0014](adr/0014-animation-and-metadata-mapping.md))의 노드 구조를 설계 문서로 정하고 구현한다(TwelveMonkeys에는 native 포맷이 없다 — [research/0001](research/0001-java-webp-landscape.md)). — 노드 구성은 TwelveMonkeys 소스 기준으로 맞췄고(WebPMetadataTest), 실제 출력 비교는 Phase 4 벤치 모듈에서 한다. native 포맷: [design/imageio-metadata.md](design/imageio-metadata.md)
- [x] 애니메이션 악성 입력(프레임 수 초과, 캔버스 밖 프레임) 테스트가 통과한다. — 서버 samples.rs(손상 애니메이션 판정), animation.rs(max_frames, 프레임 크기). 출력량 폭탄은 Phase 5

## Phase 4: JMH 벤치마크 + 결과 보고서

완료 기준:

- [x] 같은 샘플로 이 플러그인(UDS, TCP), TwelveMonkeys, darkxanter, libwebp Wasm + Chicory(ADR-0001의 경쟁 대안)를 비교한다. — 결과: [research/0009](research/0009-benchmark-results.md). 비교 대상들이 같은 이미지를 내는지 먼저 검증했다(bench/src/test)
- [ ] 결정자가 JDeli 트라이얼을 확보해 요청하면 비교에 포함한다. 트라이얼은 업무용 이메일 등록이 필요하고 평가(비운영) 용도로만 쓸 수 있으며, 만료일이 있다. 결과를 저장소 밖에 공개하기 전에 트라이얼 라이선스 파일에서 벤치마크 공개 조건을 확인한다(공개 자료로는 미확인).
- [x] 측정 조건은 [ADR-0015](adr/0015-benchmark-method.md)를 따른다(JMH 설정, 서버 컨테이너 제한, 기본 SIMD 빌드와 AVX2 빌드 모두 측정). — 추가로 JVM과 서버를 서로 다른 P코어에 고정했다(하이브리드 CPU)
- [x] 결과를 기본 SIMD 빌드 기준으로 [ADR-0011](adr/0011-hypothesis-criteria.md)의 기준(1024×768 이상 디코딩에서 TwelveMonkeys의 3배 이상, JNI의 50% 이상)으로 판정한다. — **18개 조건 중 12개 충족.** 대 TwelveMonkeys는 모두 충족(3.2–4.9배). 대 JNI는 사진 lossy·alpha의 M/L/XL 6개 조건이 미달(0.36–0.45). Phase 5 회고 ADR의 입력
- [x] libwebp를 Wasm으로 빌드해 Chicory로 실행하는 비교 구현을 `bench/`에 둔다(벤치 전용, 제품 코드 아님). 사용한 libwebp 버전과 Wasm 빌드 옵션(SIMD, 스레드)을 기록한다. — [bench/wasm/README.md](../bench/wasm/README.md)(libwebp 1.6.0, wasi-sdk 34, SIMD·스레드 없음)
- [x] 이미지 크기별(작음/중간/4K 이상), 종류별(lossy/lossless/alpha/애니메이션)로 측정한다. — XS–XL(4000×3000), 사진과 합성, 애니메이션 12프레임
- [x] 벤치마크를 한 명령으로 재현할 수 있고, 환경(CPU, OS, JDK, libwebp 버전, JMH 설정)이 결과와 함께 기록된다. — `bench/run.sh`, 결과 `bench/results/2026-10-23-phase4/environment.txt`
- [x] 결과 보고서를 `docs/research/`에 쓰고, 벤더 공개 수치([research/0001](research/0001-java-webp-landscape.md))와 비교한다. — [research/0009](research/0009-benchmark-results.md)
- [x] 청크 크기, 전송 방식(UDS/TCP), 픽셀 변환 비용에 대한 결론을 ADR-0003/0004의 확인 방법에 맞춰 적는다. — research/0009 "ADR-0003/0004 확인 방법에 대한 결론". 측정 중 서버 TCP_NODELAY 누락(요청마다 약 40 ms)을 찾아 고쳤다

## 첫 배포 전 결정

첫 배포(외부 공개나 아티팩트 배포)를 하기 전에 정한다(결정자 지시, 2026-10-23).

- [ ] 저작권자 표기(개인/조직)와 `NOTICE` 파일([ADR-0009](adr/0009-license.md))
- [ ] 원격 저장소 위치·공개 여부와 CI
- [ ] Maven Central 배포 여부. 배포하면 `rivergod.net`에 DNS TXT 레코드로 namespace를 확인받아야 한다([ADR-0010](adr/0010-maven-coordinates.md)).

## Phase 5: 보안 강화 + 성능 보강 + proto 회고

결정(결정자, 2026-10-29): Phase 4 판정(18개 중 12개 충족)에 대해 **조건부 계속**. 보안 검증을 먼저 하고, 기간을 정한 성능 보강을 한 번 거친 뒤 ADR-0011 기준(바꾸지 않는다)으로 다시 판정한다. 그 결과를 proto 회고 ADR에 담는다. 순서는 아래 단계 순이다.

### 5-1. 보안 검증

- [x] 서버 seccomp 프로파일을 만들고, 적용 상태에서 전체 통합 테스트가 통과한다. — [deploy/seccomp/webp-imageio-server.json](../deploy/seccomp/webp-imageio-server.json)(허용 55개, 기본 EPERM, x86_64). client-jvm 통합 테스트·벤치 검증·스모크 테스트가 기본으로 적용한다. 만드는 법: [deploy/README.md](../deploy/README.md) "seccomp 프로파일"
- [x] 컨테이너 제한(메모리/CPU, read-only, 네트워크 차단, non-root)이 실제로 적용됐는지 검증한다. — `deploy/scripts/verify-hardening.sh`: 설정값과 서버 프로세스의 실제 상태(/proc, cgroup) 23개 항목 통과(2026-10-29). [deploy/README.md](../deploy/README.md) "격리 적용 확인"
- [x] 서버 입력 경로(Decode, GetFeatures, DecodeAnimation) 퍼저를 만들고 실행 결과(시간, 발견 사항)를 기록한다. — [server/fuzz/](../server/fuzz/)(libFuzzer + ASan, Rust와 libwebp C 함께 계측). 대상 2개 × 30분, 실패 0건: [research/0010](research/0010-server-fuzzing.md)
- [x] 출력량 폭탄 대응을 정한다: 4096² 캔버스 × 1,000프레임 파일은 기본 상한을 모두 통과하지만 합성 출력이 약 64 GiB다(research/0005). 총 출력 픽셀 상한 같은 방안을 ADR로 제안한다. — [ADR-0017](adr/0017-output-volume-limits.md)(accepted, 구현 2026-10-29): 합성 픽셀 수 상한(기본 2^30, 애니메이션 디코딩·인코딩) + 서버 쪽 요청 시간 상한(기본 60초) + 대기 상한(기본 8). 응답을 읽지 않는 클라이언트가 슬롯을 쥐는 문제도 함께 다룬다. 테스트: `server/tests/request_limits.rs`, GIF 부하 재측정 [research/0012](research/0012-gif-conversion-load.md)

### 5-2. 성능 보강 (측정 1회분)

입력: [research/0009](research/0009-benchmark-results.md) "Phase 5 회고에 넘길 것". 범위는 아래 넷이다. SIMD 런타임 분기 빌드는 하지 않는다(libwebp 1.6.0 디코더에 해당 코드가 거의 없음을 확인). 공유 메모리 전송은 아래로 부족할 때 조사만 한다.

- [x] GetFeatures 왕복 생략: `read()`만 부를 때는 Decode 응답 헤더로 크기·상한을 확인한다(클라이언트만 변경). — 정지 이미지의 첫 `read(0)`(기본 파라미터)만. 애니메이션 여부는 파일 머리를 로컬에서 판단한다
- [x] 클라이언트 경로 프로파일링: 변환 외 비용(M lossy 1.5 ms)의 출처를 찾아 줄인다. — JFR: 조각을 임시 배열로 복사한 뒤 바이트 4개씩 조립하는 변환이 53%였다. 복사 없는 일괄 변환(빅엔디언 IntBuffer + 회전)으로 바꿔 3.5%로 줄였다. 남은 클라이언트 비용은 gRPC/protobuf가 받은 바이트를 `byte[]`로 복사하는 부분이다
- [x] ~~픽셀 포맷 `BGRA8` 추가~~ — **하지 않는다.** 일괄 변환 뒤 변환 비용이 프로파일의 3.5%라, BGRA8이 없앨 수 있는 회전은 그 일부다. proto 변경에 비해 이득이 작다. **제외 확정**(결정자, 2026-10-29). 픽셀 포맷은 RGBA8 하나를 유지한다([ADR-0019](adr/0019-prototype-retrospective.md) proto 안건)
- [x] 서버 할당자: (a) 출력 버퍼 재사용을 먼저 재고, 부족하면 (b) glibc 기반 이미지를 ADR-0012를 대체하는 ADR로 제안한다. (a)는 만료 정책(재사용 크기 상한, 개수·총량 상한, 유휴 만료, 메모리 압박 시 우선 해제, 실패 시 이전 픽셀 노출 방지)을 함께 정한다. — 0 채우기 생략은 반영했다. 재사용은 glibc보다도 효과가 커서(서버 안 XL lossy −21%) (a)로 [ADR-0018](adr/0018-decode-output-buffer-pool.md)을 썼다. 크기 상한 대신 총량 상한(128 MiB)과 유휴 만료(30초)로 묶는다. **확정**(accepted, 결정자 2026-10-29) 후 `main`에 병합했다
- [x] `bench/run.sh`로 ADR-0011을 다시 판정하고 research 문서로 기록한다. — **18개 조건 중 16개 충족**(Phase 4: 12개). 남은 미달은 사진 alpha M(0.45)·L(0.47)이고, 원인은 서버 안 libwebp alpha 디코딩 시간이다(AVX2로도 줄지 않음). [research/0011](research/0011-phase5-performance.md). 버퍼 풀(ADR-0018)을 포함한 빌드로 쟀다

### 5-3. proto 회고

- [x] proto 회고 ADR(계속/중단 판단과 근거)을 `proposed`로 작성한다. — [ADR-0019](adr/0019-prototype-retrospective.md): **사이드카 구조로 확정**(accepted, 결정자 2026-10-29). 16/18 충족, 남은 alpha 격차는 서버 안 libwebp 쪽이라 후속 조사. `webp.v1` 유지(필드 추가만). 입력: Phase 4 판정, 5-1 보안 검증 결과, 5-2 재판정, proto 변경 안건(픽셀 포맷, 총 출력 픽셀 상한, GetFeatures/Decode 중복). 계속/중단 결정은 결정자가 한다.
- [x] ImageIO 설정 주입(공개 설정 타입, SPI 수동 등록, ReadParam/WriteParam) ADR을 `proposed`로 작성한다. — [ADR-0020](adr/0020-imageio-config-injection.md)(proposed). 개정(2026-10-30): lossless `exact` 제외, "server busy" 재시도(기본 끔) 추가, 등록 단위 설정은 JVM에 하나임을 확인
- [ ] ADR-0020 구현과 테스트(결정자 요청으로 확정 전에 시험 구현). — 브랜치 `feat/adr-0020-config-injection`. 확정되면 main에 병합한다
