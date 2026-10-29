# docs

이 프로젝트의 모든 결정과 조사는 이 디렉터리에서 관리한다. **코드보다 문서가 먼저다.**

## 구조

```
docs/
  README.md        이 문서. 인덱스와 운영 규칙
  roadmap.md       단계별 계획, 완료 기준, 진행 상태
  research/        조사 결과 (사실 기록, 출처 포함)
  adr/             Architecture Decision Records (MADR 형식)
  design/          설계 문서 (목표·제약, API, 시퀀스)
  conventions/     작업 방식, 주석/문서화, 테스트, git 규칙
```

## 운영 규칙

### ADR (`adr/`)

- 형식은 MADR이다. [0000-template.md](adr/0000-template.md)를 복사해 `NNNN-제목.md`로 만든다. 번호는 순서대로 붙이고 재사용하지 않는다.
- 필드: 상태, 맥락, 결정 동인, 검토한 선택지, 결정, 결과(긍정/부정), 확인 방법, 선택지별 장단점.
- 상태 값은 `proposed` / `accepted` / `superseded by NNNN` / `rejected` 중 하나다.
- **AI 에이전트가 작성하는 ADR은 모두 `proposed`다.** `accepted`(또는 `rejected`)로 바꾸는 것은 사람이 승인한 뒤에만 한다. 바꾼 사람과 날짜를 ADR 머리말에 적는다.
- 결정을 뒤집을 때 기존 ADR 본문을 고치지 않는다. 새 ADR을 쓰고 기존 ADR의 상태만 `superseded by NNNN`으로 바꾼다.
- `proposed` 상태에서는 사실 오류 수정과 추천안 갱신을 할 수 있다.

### 조사 (`research/`)

- 파일 이름은 `NNNN-제목.md`. 머리말에 작성일·확인일과 관련 ADR을 적는다.
- 사실 주장에는 출처를 단다: URL, 커밋 해시, 파일 경로(+ 줄 번호), 확인 날짜.
- 추정은 "추정", 확인하지 못한 것은 "미확인"이라고 명시한다. 추측으로 빈칸을 채우지 않는다.
- 사전 조사나 이전 문서와 다른 사실을 찾으면 "달라진 점"으로 따로 적는다.
- 조사 문서는 사실 기록이다. 결정과 추천은 ADR에 쓴다(조사 문서의 요약에 추천 방향을 적는 것은 괜찮다).

### 설계 (`design/`)

- 구현이 따라야 하는 구조, 계약, 시퀀스를 쓴다. 결정 근거는 ADR을 링크한다.
- 설계를 바꾸는 코드 변경은 해당 설계 문서를 같은 커밋 또는 먼저 고친다.

## 인덱스

### 계획

- [roadmap.md](roadmap.md) — Phase 0~5와 각 완료 기준, 첫 배포 전 결정 사항

### 설계

- [design/goals-and-constraints.md](design/goals-and-constraints.md) — 목표, 비목표, 핵심 설계 제약
- [design/grpc-api.md](design/grpc-api.md) — gRPC API(`webp.v1`): 스트리밍 규칙, 상한, 오류 매핑, 클라이언트 설정
- [design/imageio-metadata.md](design/imageio-metadata.md) — ImageIO 메타데이터 포맷, 애니메이션 읽기·쓰기 매핑

### ADR

| 번호 | 제목 | 상태 |
|---|---|---|
| [0000](adr/0000-template.md) | 템플릿 | - |
| [0001](adr/0001-isolate-libwebp-in-grpc-sidecar.md) | libwebp를 별도 프로세스(gRPC 사이드카)로 격리 | accepted |
| [0002](adr/0002-server-language.md) | 서버 구현 언어: Rust (tonic + libwebp-sys) | accepted |
| [0003](adr/0003-transport.md) | 전송 방식: UDS 기본, TCP 옵션, 1 MiB 청크 스트리밍 | accepted |
| [0004](adr/0004-pixel-transfer-format.md) | 픽셀 전송 포맷: RGBA8 비-premultiplied, 행 패딩 없음 | accepted |
| [0005](adr/0005-jdk-language-build.md) | 클라이언트: JDK 17, Java, Gradle | accepted |
| [0006](adr/0006-test-samples.md) | 테스트 샘플 세트 구성과 라이선스 | accepted |
| [0007](adr/0007-agent-glue.md) | AI 에이전트 규칙: AGENTS.md 단일 원본 + 얇은 연결 파일 | accepted |
| [0008](adr/0008-libwebp-version-pinning.md) | libwebp 버전 고정: 최신 릴리스 태그 + main 미릴리스 수정 추적 | accepted |
| [0009](adr/0009-license.md) | 프로젝트 라이선스: Apache-2.0 | accepted |
| [0010](adr/0010-maven-coordinates.md) | Maven 좌표: `net.rivergod:webp-imageio-external-server-proto` | accepted |
| [0011](adr/0011-hypothesis-criteria.md) | 가설 판정 기준: 디코딩 처리량 TwelveMonkeys 3배 이상, JNI 50% 이상 | accepted |
| [0012](adr/0012-server-runtime-config.md) | 서버 실행 구성: 이름, 설정, 상한 기본값, 동시 처리, 컨테이너, 소켓 | accepted |
| [0013](adr/0013-client-package-and-imageio-mapping.md) | 클라이언트 패키지와 ImageIO 매핑 | accepted |
| [0014](adr/0014-animation-and-metadata-mapping.md) | 애니메이션·메타데이터 매핑 | accepted |
| [0015](adr/0015-benchmark-method.md) | 벤치마크 방법: 환경, 샘플, SIMD 빌드, Wasm 비교 구현 | accepted |
| [0016](adr/0016-memory-budget-admission.md) | 메모리 예산 기반 요청 허용 | proposed |
| [0017](adr/0017-output-volume-limits.md) | 요청당 작업량 상한: 합성 픽셀 수 상한과 서버 쪽 요청 시간 상한 | proposed |
| [0018](adr/0018-decode-output-buffer-pool.md) | 정지 이미지 디코딩 출력 버퍼 재사용(버퍼 풀)과 만료 정책 | proposed |

### 조사

- [research/0001-java-webp-landscape.md](research/0001-java-webp-landscape.md) — Java WebP 라이브러리 현황(소스 검증)
- [research/0002-agent-glue.md](research/0002-agent-glue.md) — AI 에이전트 연결 파일 동작 확인
- [research/0003-server-language-rust-vs-go.md](research/0003-server-language-rust-vs-go.md) — 서버 언어 비교 + 스파이크 결과
- [research/0004-jdk-baseline.md](research/0004-jdk-baseline.md) — JDK 최소 버전, Spring 지원 현황, Java vs Kotlin, 빌드 도구
- [research/0005-test-samples.md](research/0005-test-samples.md) — 테스트 샘플 후보, 라이선스, RFC 9649 절 대응표
- [research/0006-license.md](research/0006-license.md) — 라이선스 후보, 의존성 라이선스와 호환성
- [research/0007-server-memory-budget.md](research/0007-server-memory-budget.md) — 서버 메모리 예산 측정(정지·애니메이션)
- [research/0008-java-uds-poc.md](research/0008-java-uds-poc.md) — Java UDS PoC, 변환 비용, JPMS
- [research/0009-benchmark-results.md](research/0009-benchmark-results.md) — Phase 4 벤치마크 결과와 ADR-0011 가설 판정
- [research/0010-server-fuzzing.md](research/0010-server-fuzzing.md) — 서버 입력 경로 퍼징(libFuzzer + ASan) 결과

### 작업 규칙

- [conventions/working-principles.md](conventions/working-principles.md) — 작업 원칙 4가지(출처·라이선스 포함)
- [conventions/code-documentation.md](conventions/code-documentation.md) — 주석과 문서화
- [conventions/testing.md](conventions/testing.md) — 테스트 작성
- [conventions/git-workflow.md](conventions/git-workflow.md) — 커밋, 브랜치, 병합(`--no-ff`)
