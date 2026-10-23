# AGENTS.md

이 파일은 이 저장소에서 일하는 사람과 AI 에이전트(Claude Code, Codex CLI, Gemini CLI, Cursor 등)가 따르는 **단일 원본 규칙**이다. 도구별 파일(`CLAUDE.md`, `GEMINI.md`, `.gemini/settings.json`, `.cursor/rules/`)은 이 파일을 불러오기만 하고 규칙을 복사하지 않는다([ADR-0007](docs/adr/0007-agent-glue.md)).

## 프로젝트

libwebp를 별도 프로세스(gRPC 사이드카 서버)로 격리하고, JVM에서는 그 서버를 호출하는 ImageIO SPI 플러그인으로 WebP를 읽고 쓰는 구조를 검증하는 **프로토타입**이다. 목표는 네이티브 수준 속도, 크래시·취약점 격리, 애니메이션 합성과 ICC/EXIF/XMP 읽기·쓰기를 JNI 없이 얻는 것이다.

- 목표, 비목표, 설계 제약: [docs/design/goals-and-constraints.md](docs/design/goals-and-constraints.md)
- 현재 단계와 완료 기준: [docs/roadmap.md](docs/roadmap.md) — **현재 단계 범위를 넘는 구현을 하지 않는다.**

## 디렉터리

```
proto/        gRPC/Protobuf 정의 (webp/v1)
server/       libwebp gRPC 서버 (Rust: ADR-0002)
client-jvm/   ImageIO SPI 플러그인 (Java 17, Gradle: ADR-0005)
bench/        JMH 벤치마크 (별도 모듈)
samples/      테스트 이미지 + SOURCES.md(출처·라이선스·해시)
deploy/       Dockerfile, compose, 보안 프로파일
spikes/       결정용 버릴 코드. 프로덕션 코드로 가져오지 않는다.
docs/         조사(research), 결정(adr), 설계(design), 규칙(conventions), 로드맵
```

## 결정과 문서 규칙

- **결정은 `docs/adr/`에, ADR은 `proposed`로만 작성한다.** `accepted`로 바꾸는 것은 사람의 승인 후에만 한다.
- ADR은 MADR 형식([템플릿](docs/adr/0000-template.md))을 따르고, 선택지 비교와 추천안을 넣는다.
- 결정을 뒤집을 때 기존 ADR 본문을 고치지 않는다. 새 ADR을 쓰고 기존 ADR의 상태만 `superseded by NNNN`으로 바꾼다.
- 조사 결과는 `docs/research/`에 쓴다. 사실 주장에는 출처(URL, 커밋 해시, 파일 경로, 확인 날짜)를 단다. 추정은 "추정", 확인 못 한 것은 "미확인"이라고 쓴다. 추측으로 빈칸을 채우지 않는다.
- 코드보다 문서가 먼저다. 설계를 바꾸는 코드는 해당 문서·ADR을 먼저(또는 같은 커밋에서) 고친다.
- 문서 인덱스: [docs/README.md](docs/README.md)

## 작업 원칙

상세와 출처: [docs/conventions/working-principles.md](docs/conventions/working-principles.md). 속도보다 신중함 쪽으로 기운 원칙이다. 사소한 작업에는 판단껏 적용한다.

1. **코딩 전에 생각한다** — 가정을 명시하고, 해석이 여럿이면 제시하고, 불명확하면 멈추고 묻는다.
2. **단순함이 먼저다** — 문제를 푸는 최소한의 코드. 요청받지 않은 기능·추상화·설정 가능성을 넣지 않는다.
3. **외과적으로 변경한다** — 요청과 관련된 줄만 바꾼다. 내 변경으로 안 쓰이게 된 것만 지운다.
4. **목표 기반으로 실행한다** — 검증 가능한 성공 기준을 정하고, 여러 단계 작업은 `1. [단계] → 검증: [확인 방법]` 형식의 계획을 먼저 적는다.

> 위 4원칙은 [forrestchang/andrej-karpathy-skills](https://github.com/forrestchang/andrej-karpathy-skills)의 `CLAUDE.md`를 발췌·번역했다. MIT License. 저작권 및 라이선스 고지는 [working-principles.md](docs/conventions/working-principles.md)에 있다.

## 주석과 문서화

상세: [docs/conventions/code-documentation.md](docs/conventions/code-documentation.md)

- 문서와 주석은 한국어, 식별자·로그·오류 메시지는 영어.
- 공개 API에는 언어별 표준 문서 주석(Javadoc, KDoc, rustdoc, godoc, proto 선행 주석)을 단다. 목적, 단위(바이트·픽셀·ms), 오류 조건, 스레드 안전성, 소유권/수명, 스트림 정지 조건을 담는다.
- 함수 안의 논리 블록 앞에 **무엇을 왜** 하는지 한두 줄. 코드를 읽어 주는 주석은 쓰지 않는다.
- 비자명한 결정에는 근거를 남기고 ADR 번호를 참조한다(`// ADR-0003: ...`). WebP 스펙 의존 코드는 RFC 9649 절을 참조한다.

## 테스트

상세: [docs/conventions/testing.md](docs/conventions/testing.md)

- FIRST 원칙, AAA 구조(Act는 한 줄), 테스트 하나에 동작 하나, 이름은 `대상_상황_기대결과`.
- 계층: 단위(로컬) / 통합(실제 서버, Testcontainers 등) / 적합성(libwebp 테스트 벡터) / 벤치(JMH, `bench/`).
- lossless는 픽셀 정확 일치, lossy는 이름 붙은 PSNR/SSIM 임계값 상수(근거 주석 포함)로 비교한다.
- 손상/악성 입력(잘린 파일, 크기 필드 조작, 거대 크기 선언) 테스트를 반드시 포함한다.
- 버그 수정은 재현 테스트를 먼저 추가한다.

## 빌드 / 테스트 / 린트 명령

| 대상 | 명령 | 비고 |
|---|---|---|
| proto lint | `docker run --rm --network none -e BUF_CACHE_DIR=/tmp/buf-cache -v "$PWD:/workspace:ro" -w /workspace bufbuild/buf:1.75.0 lint` | 저장소 루트에서 실행 |
| server 검사·테스트 | `cd server && cargo fmt --check && cargo clippy --all-targets -- -D warnings && cargo test` | 단위 + 통합(프로세스 안 UDS 서버). 툴체인: [server/README.md](server/README.md) |
| server 의존성 라이선스 | `cd server && cargo deny check licenses sources` | 의존성을 바꾸면 고지 재생성: `cargo about generate --frozen about.hbs -o licenses/THIRD-PARTY-NOTICES.txt` |
| 서버 이미지 | `docker build -f deploy/Dockerfile -t webp-imageio-server:dev .` | 컨테이너 스모크 테스트·메모리 측정: [deploy/README.md](deploy/README.md) |
| 샘플 검사 | `python3 -I samples/scripts/manifest.py --check` | 다시 만들기·적합성 데이터 받기: [samples/README.md](samples/README.md) |
| 서버 적합성(공식 벡터) | `cd server && cargo test --test conformance -- --ignored` | samples/conformance를 먼저 받는다 |
| client-jvm 단위 테스트 | `./gradlew :client-jvm:test` | 컴파일에 `-Werror`와 공개 API javadoc 검사(doclint) 포함 |
| client-jvm 통합 테스트 | `./gradlew :client-jvm:integrationTest` | Docker 필요(서버 이미지를 먼저 빌드한다). JDK 매트릭스: `:client-jvm:testMatrix`. 그 밖의 작업: [client-jvm/README.md](client-jvm/README.md) |
| bench | `bench/run.sh` (연결 확인만: `bench/run.sh smoke`) | Docker, taskset, Rust musl 타깃 필요. 전체 약 4시간. 상세: [bench/README.md](bench/README.md) |

## 금지 사항

- **심볼릭 링크를 만들지 않는다.** Windows에서 깨지고 일부 에디터·에이전트 도구가 링크를 통해 쓰지 않는다. 같은 내용이 필요하면 import나 링크(문서 참조)로 해결한다.
- ADR 상태를 `accepted`로 바꾸지 않는다(사람만). accepted ADR의 본문을 고치지 않는다.
- 요청마다 `cwebp`/`dwebp` 같은 CLI를 실행하는 서버 구현을 하지 않는다. 서버는 libwebp를 직접 링크한다.
- JVM 클라이언트에 JNI나 네이티브 라이브러리를 넣지 않는다.
- `ImageReaderSpi.canDecodeInput`에서 네트워크를 호출하지 않는다(로컬 매직 바이트 검사만).
- 출처·라이선스가 `samples/SOURCES.md`에 없는 이미지를 `samples/`에 커밋하지 않는다. `spikes/testdata/`처럼 다른 곳에 두는 작은 자체 생성 파일은 그 디렉터리의 README에 생성 방법과 해시를 적는다.
- `spikes/` 코드를 프로덕션 디렉터리로 옮기지 않는다. 필요한 것은 다시 작성한다.
- 비밀값(토큰, 키, `.env`)을 커밋하지 않는다.

## 기타 규칙

- 파일은 UTF-8, LF 줄바꿈이다(`.gitattributes`, `.editorconfig`). Windows 전용 스크립트(`.bat`, `.cmd`, `.ps1`)만 CRLF.
- git 작업 방식 상세: [docs/conventions/git-workflow.md](docs/conventions/git-workflow.md)
  - 작업은 `main`에서 만든 브랜치(`<type>/<짧은-설명>`)에서 하고, `main`에 직접 커밋하지 않는다.
  - 커밋은 논리 단위로 나누고 작업이 길면 주요 항목마다 커밋한다. 메시지는 `<type>: <요약>` 형식이다(type: `feat`, `fix`, `docs`, `test`, `build`, `chore`, `refactor`). 요약은 한국어로 써도 된다.
  - `main`에는 `git merge --no-ff <브랜치>`로 병합한다(fast-forward 금지). 병합한 브랜치는 지운다.
## 이 파일과 연결 파일을 고칠 때

도구별 동작 근거: [docs/research/0002-agent-glue.md](docs/research/0002-agent-glue.md)

- 규칙은 이 파일(과 하위 `AGENTS.md`)에만 쓴다. `CLAUDE.md`, `.gemini/settings.json`, `.cursor/rules/*.mdc`에 규칙을 복사하지 않는다.
- 이 파일에서 `@경로` import를 쓰지 않는다(Codex, Cursor는 펼치지 않는다). 다른 문서는 일반 링크로 참조한다. `@`로 시작하는 단어(Java 애노테이션 등)는 반드시 백틱 안에 쓴다. 그렇지 않으면 Claude Code, Gemini CLI가 import로 해석할 수 있다.
- 하위 디렉터리에 언어별 규칙이 필요해지면 그 디렉터리에 `AGENTS.md`를 두고, **같은 디렉터리에 `@AGENTS.md` 한 줄짜리 `CLAUDE.md`도 둔다**(루트 `CLAUDE.md`가 있으면 Claude Code는 하위 `AGENTS.md`를 직접 읽지 않는다). 하위 파일은 루트 규칙을 덮어쓰지 않고 보탠다.
- Codex CLI는 저장소 루트부터 작업 디렉터리까지의 `AGENTS.md`만 읽는다. 하위 디렉터리 규칙이 중요한 작업은 그 디렉터리에서 실행한다.
- 루트와 하위 `AGENTS.md`의 합계를 32 KiB 미만으로 유지한다(Codex CLI 기본 한도).
- `AGENTS.override.md`를 커밋하지 않는다(Codex만 이 파일을 우선해 `AGENTS.md`를 건너뛴다).
