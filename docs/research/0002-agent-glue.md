# 0002. AI 에이전트 연결 파일(agent glue) 동작 확인

- 상태: 초안
- 작성일/확인일: 2026-10-23
- 관련 ADR: ADR-0007

## 요약

- 네 도구 모두 루트 `AGENTS.md` 하나를 공통 원본으로 쓸 수 있다. 심볼릭 링크는 필요 없다.
- **Codex CLI**와 **Cursor**는 `AGENTS.md`를 기본으로 읽는다. 연결 파일이 필요 없다.
- **Claude Code**는 v2.1.277부터 `CLAUDE.md`가 없을 때만 `AGENTS.md`를 직접 읽는다 [S1]. 공식 문서가 권하는 공유 방식은 `CLAUDE.md` 첫 줄의 `@AGENTS.md` import다. 이 방식은 모든 버전과 세션에서 동작하고, 중복 로드도 일어나지 않는다 [S1].
- **Gemini CLI**는 기본값으로 `GEMINI.md`만 읽는다. `.gemini/settings.json`의 `context.fileName`(문자열 또는 문자열 배열)에 `AGENTS.md`를 넣으면 된다 [S7][S8].
- 주의할 점이 네 가지 있다.
  1. Cursor는 루트 `CLAUDE.md`도 **항상 적용**한다 [S13]. 따라서 `CLAUDE.md`에 쓴 Claude 전용 지시도 Cursor에 들어간다. `CLAUDE.md`는 import 한 줄과 최소한의 내용만 둔다.
  2. 하위 디렉터리 `AGENTS.md`를 다루는 방식이 도구마다 다르다. 특히 루트에 `CLAUDE.md`가 있으면 Claude Code는 하위 `AGENTS.md`를 직접 읽지 않는다 [S1]. 그래서 하위 `AGENTS.md` 옆에도 `@AGENTS.md` 한 줄짜리 `CLAUDE.md`를 둔다.
  3. Codex와 Cursor는 `@path` import를 펼치지 않는다 [S6][S12]. 따라서 `AGENTS.md` 안에서는 import를 쓰지 않고 일반 경로 링크만 쓴다. Java 애노테이션처럼 `@`로 시작하는 단어는 반드시 백틱으로 감싼다. Claude Code와 Gemini CLI는 코드 스팬 안의 `@`를 import로 보지 않는다 [S1][S8].
  4. Google은 2026-06-18부터 Google AI Pro/Ultra 등 개인 사용자에게 Gemini CLI 대신 Antigravity CLI를 제공한다 [S10]. Antigravity CLI도 워크스페이스의 `GEMINI.md`와 `AGENTS.md`를 읽는다고 안내한다 [S11].
- andrej-karpathy-skills 커밋 `2c606141`의 존재를 확인했다. 이 저장소에는 **LICENSE 파일과 저작권 줄이 없다**. MIT 라이선스라는 표시는 README, `plugin.json`, `SKILL.md` 세 곳에만 있다 [S15].

## 도구별 확인 결과

| 도구 | 기본 파일명 | import 문법 | 하위 디렉터리 적용 | 설정 위치 | 출처 |
| --- | --- | --- | --- | --- | --- |
| Claude Code | `CLAUDE.md`(`./` 또는 `./.claude/`), `CLAUDE.local.md`. v2.1.277부터는 `CLAUDE.md`가 없을 때만 `AGENTS.md`를 대신 읽음 | `@path`. 상대 경로는 import를 담은 파일 기준, 최대 4단계, 코드 스팬과 펜스 블록은 건너뜀 | 상위 디렉터리 파일은 시작할 때 읽음. 하위 디렉터리 `CLAUDE.md`는 그 디렉터리의 파일을 읽거나 쓰거나 편집할 때 읽음 | `.claude/settings.json`(커밋), `.claude/settings.local.json`(개인용), `~/.claude/settings.json` | S1, S2, S3 |
| Codex CLI | `AGENTS.md`. 디렉터리마다 `AGENTS.override.md` → `AGENTS.md` → fallback 이름 순으로 찾고 한 파일만 씀 | 없음. 파일 내용을 그대로 이어 붙임(소스 확인) | 프로젝트 루트(`.git`)에서 cwd까지의 파일만 이어 붙임. cwd 아래 하위 디렉터리는 자동으로 읽지 않고, 기본 프롬프트가 모델에게 직접 확인하라고 지시함 | `~/.codex/config.toml`(`CODEX_HOME`), 신뢰된 프로젝트에서만 `.codex/config.toml` | S4, S5, S6 |
| Gemini CLI | `GEMINI.md`. `context.fileName`으로 바꾸거나 추가 | `@./file.md`, `@/abs/path.md`. 최대 깊이 5(기본값), 코드 영역 안은 무시, 순환 import 차단 | 전역 → 작업 디렉터리와 그 상위(`.git` 경계까지) → 도구가 파일에 접근할 때 하위 파일을 즉시 읽음(JIT). 이 부분은 문서끼리 서술이 다름(아래 참고) | `.gemini/settings.json`(프로젝트), `~/.gemini/settings.json`(사용자) | S7, S8, S9 |
| Cursor | 루트와 하위 `AGENTS.md`, 루트 `CLAUDE.md`(항상 적용), `.cursor/rules/**/*.mdc` | 규칙 안의 `@file`은 위치만 알려줌. 내용을 펼치지 않고 Agent가 도구로 읽음 | 하위 `AGENTS.md`는 그 디렉터리와 그 아래에 적용. 상위 파일과 합치며, 더 구체적인 파일이 우선 | `.cursor/rules/`(프로젝트), `~/.cursor/rules`(사용자 파일), Customize의 User/Team Rules | S12, S13, S14 |

### Claude Code

- **AGENTS.md 직접 읽기** [S1]
  - v2.1.277부터 내장 플러그인(`cc-plugin-agents-md@builtin`, v2.1.285 이전 이름은 `agents-md@builtin`)이 `AGENTS.md`를 직접 읽는다.
  - 기본값 `claude-md-or-agents-md`에서는 작업 디렉터리나 그 위에 `CLAUDE.md`, `.claude/CLAUDE.md`, `CLAUDE.local.md` 중 하나라도 있으면 **`CLAUDE.md`만** 읽는다.
  - **Project instructions** 설정(`/config`)으로 동작을 바꿀 수 있다. 다만 settings 파일에 이 값을 둘 경우 사용자 설정, `--settings`, 관리형 설정에서만 읽고 **프로젝트와 로컬 settings에서는 무시한다**. 저장소 차원에서는 이 값을 강제할 수 없다.
  - 일부 세션은 `AGENTS.md`를 직접 읽지 못한다. 구버전, 플러그인을 끈 경우, 업그레이드 직후 첫 세션 등이다.
  - 직접 읽지 않는 파일: `AGENTS.local.md`, `AGENTS.override.md`, `.agents/` 아래 파일.
  - 이 저장소 로컬 환경의 Claude Code는 2.1.284다(`claude --version`, 2026-10-23).
- **공식 권장 연결 방식** [S1]
  - "Share one file with other coding tools" 절은 `AGENTS.md` 옆의 `CLAUDE.md`에 `@AGENTS.md`를 두라고 한다. 그 아래에 Claude 전용 지시를 쓰면 import한 파일을 먼저 읽고 나머지를 읽는다.
  - 이 import를 남겨 둬도 어떤 설정값에서든 `AGENTS.md`를 두 번 읽지 않는다.
  - 심볼릭 링크는 두 가지 이유로 권하지 않는다. Edit/Write 도구는 링크를 통해 쓰지 않는다. Windows에서는 링크를 만들려면 관리자 권한이나 개발자 모드가 필요하고, `core.symlinks`가 꺼져 있으면 Git이 링크를 한 줄짜리 텍스트 파일로 체크아웃한다. 문서는 Windows 사용자가 있으면 import를 쓰라고 명시한다.
- **`@path` import** [S1]
  - 상대 경로와 절대 경로를 모두 쓸 수 있다. 상대 경로는 작업 디렉터리가 아니라 **import를 담은 파일**을 기준으로 해석한다.
  - 재귀 import는 **최대 4단계(hop)**까지다.
  - **마크다운 코드 스팬과 펜스 코드 블록 안의 `@`는 import로 처리하지 않는다**. `` `@README` ``는 글자 그대로 남는다.
  - 공백이 있는 경로는 `\ `로 이스케이프한다. 따옴표로 감싼 경로는 import하지 않는다.
  - 작업 디렉터리 밖을 가리키는 import는 처음 만날 때 승인 대화상자가 뜬다.
  - import는 정리 수단일 뿐이다. 가져온 파일도 함께 로드되므로 컨텍스트 비용은 줄지 않는다.
- **계층 로딩** [S1]
  - cwd와 그 상위 디렉터리의 `CLAUDE.md`와 `CLAUDE.local.md`는 시작할 때 모두 이어 붙인다. 순서는 루트에서 cwd 방향이다.
  - 하위 디렉터리의 `CLAUDE.md`는 Claude가 그 디렉터리의 다른 파일을 Read/Write/Edit할 때 한 번 로드한다. `cat`, `head`처럼 읽기로 간주되는 Bash 명령도 포함한다.
  - 필요할 때 로드하는 파일에는 파일당 크기 상한이 있다. 200K 컨텍스트에서는 40,000자다.
  - 블록 단위 HTML 주석(`<!-- -->`)은 컨텍스트에 넣기 전에 제거한다.
- **`.claude/` 규칙** [S2][S3]
  - `.claude/settings.json`은 팀 공유용이므로 커밋한다.
  - `.claude/settings.local.json`은 개인용이다. Claude Code가 이 파일을 처음 쓸 때 전역 git excludes에 `**/.claude/settings.local.json`을 추가한다. 직접 만든 경우에는 `.gitignore`에 직접 넣어야 한다.
  - 우선순위는 높은 것부터 관리형 → 명령줄 → 로컬 → 공유 프로젝트 → 사용자 순이다.
  - `CLAUDE.local.md`는 `.gitignore`에 넣는다.
  - `rules/*.md`(frontmatter는 `paths`만 사용), `skills/`, `agents/`, `commands/`는 커밋 대상이다.

### Codex CLI

- **탐색 순서** [S4][S6]
  1. 전역: `CODEX_HOME`(기본 `~/.codex`)에서 `AGENTS.override.md`, `AGENTS.md` 순으로 찾고 비어 있지 않은 첫 파일 하나만 쓴다.
  2. 프로젝트: `project_root_markers`(기본값 `[".git"]`)로 루트를 찾는다. 루트에서 cwd까지 내려가며 디렉터리마다 `AGENTS.override.md` → `AGENTS.md` → `project_doc_fallback_filenames` 순으로 확인한다. **디렉터리당 최대 한 파일**이다. 루트를 못 찾으면 cwd만 본다. 루트보다 위로는 올라가지 않는다.
- **병합**: 루트 쪽 파일부터 빈 줄로 이어 붙인다. cwd에 가까운 파일이 뒤에 오므로 앞의 지시보다 우선한다. 빈 파일은 건너뛴다 [S4].
- **하위 디렉터리**
  - cwd보다 아래에 있는 `AGENTS.md`는 시작할 때 읽지 않는다 [S4][S6].
  - 대신 기본 프롬프트(`AGENTS.md spec`)가 모델에게 지시한다. 하위 디렉터리에서 작업하면 해당 `AGENTS.md`를 직접 확인하고, 더 깊은 파일의 지시를 우선하라는 내용이다 [S6]. 실제 적용은 모델의 판단에 달려 있다(추정).
- **크기 한도**: 합친 크기가 `project_doc_max_bytes`(기본 **32 KiB**)에 이르면 더 추가하지 않는다. 경계에 걸린 파일은 잘린다 [S4][S6].
- **fallback 파일명**: `project_doc_fallback_filenames = ["TEAM_GUIDE.md", ".agents.md"]`처럼 설정한다. 목록에 없는 이름은 무시한다 [S4][S5].
- **신뢰**
  - 프로젝트 범위의 `.codex/` 설정은 신뢰된 프로젝트에서만 읽는다 [S5].
  - 소스상으로는 신뢰되지 않은 프로젝트에서 프로젝트 `AGENTS.md`도 읽지 않는다(`active_project.is_untrusted()` 분기) [S6]. 공식 문서에는 이 내용이 없다.
- **`AGENTS.override.md`**: 같은 디렉터리의 `AGENTS.md`보다 먼저 선택된다. 실수로 커밋하면 Codex만 다른 규칙을 읽게 되므로 `.gitignore`에 넣는다(권장).

### Gemini CLI

- **파일명 설정** [S7][S8][S9]
  - 키는 `context.fileName`이고 타입은 `string | string[]`이다. 기본 파일명은 `GEMINI.md`다(`DEFAULT_CONTEXT_FILENAME`).
  - main 브랜치 소스(`a713410a`)의 `setGeminiMdFilename`은 설정값을 기본값 `GEMINI.md`에 **추가**한다. 따라서 `["AGENTS.md"]`만 써도 `GEMINI.md`는 계속 탐색한다. 릴리스 버전별로 동작이 다른지는 확인하지 못했다.
- **설정 파일**
  - 프로젝트는 `.gemini/settings.json`, 사용자는 `~/.gemini/settings.json`이다. 프로젝트 설정이 사용자 설정보다 우선한다 [S8].
  - Trusted Folders 기능은 기본으로 꺼져 있다. 켠 상태에서 신뢰되지 않은 폴더라면 **`.gemini/settings.json`을 읽지 않는다** [S8]. 이 경우 `AGENTS.md` 연결도 끊긴다.
- **import**
  - `@./file.md`, `@../file.md`, `@/abs/path.md` 형식이다.
  - 중첩 import를 허용한다. 순환 import는 감지해 차단한다.
  - 최대 깊이 기본값은 5다.
  - `marked` 라이브러리로 코드 블록과 인라인 코드 스팬을 찾아내고, 그 안의 `@`는 무시한다.
  - 대상 파일이 없으면 오류 주석을 넣고 계속 진행한다 [S8].
- **계층 로딩**
  - 전역 `~/.gemini/GEMINI.md`를 먼저 읽고, 이어서 작업 디렉터리와 그 상위 디렉터리를 읽는다. 상위 탐색은 `context.memoryBoundaryMarkers`(기본 `[".git"]`)에서 멈춘다.
  - 하위 디렉터리에 대해서는 문서끼리 서술이 **다르다**.
    - `docs/cli/gemini-md.md`: 도구가 파일이나 디렉터리에 접근할 때 그 위치와 상위 디렉터리를 신뢰 루트까지 탐색한다(JIT) [S7].
    - `docs/reference/configuration.md`: 시작할 때 cwd 아래 하위 디렉터리를 최대 200개(`context.discoveryMaxDirs`)까지 탐색한다 [S8].
    - main 소스에는 JIT 구현이 있다(`read_file`, `write_file` 등에서 `loadJitSubdirectoryMemory`). JIT 탐색도 설정된 파일명 목록(`getAllGeminiMdFilenames`)을 쓴다 [S9]. 따라서 하위 `AGENTS.md`도 대상이 된다.
- **AGENTS.md 기본 지원 여부**
  - 기본값으로는 읽지 않는다. 문서에서 `AGENTS.md`는 `context.fileName` 예시에만 나온다 [S7][S8].
- **제품 전환**
  - Google AI Pro/Ultra와 개인용 무료 Gemini Code Assist 사용자는 2026-06-18부터 Gemini CLI를 쓸 수 없다. 유료 API 키와 기업 라이선스 사용자는 계속 쓸 수 있다 [S10].
  - Antigravity CLI 마이그레이션 문서는 두 CLI가 같은 워크스페이스 규칙을 쓰며, 워크스페이스의 `GEMINI.md`와 `AGENTS.md`, 전역 `~/.gemini/GEMINI.md`를 읽는다고 안내한다 [S11]. Antigravity CLI가 `.gemini/settings.json`의 `context.fileName`을 따르는지는 확인하지 못했다.

### Cursor

- **AGENTS.md** [S12][S13]
  - 루트와 하위 디렉터리의 `AGENTS.md`를 모두 지원한다.
  - 하위 파일은 그 디렉터리와 그 아래 파일을 다룰 때 적용된다. 상위 파일과 합쳐지며, 더 구체적인 지시가 우선한다.
  - metadata는 없다.
- **CLAUDE.md**: Cursor는 `CLAUDE.md`를 `AGENTS.md`와 같은 방식으로 읽는다. `alwaysApply` 값과 관계없이 **모든 대화에 항상 적용**한다 [S13].
- **Cursor CLI**: 편집기와 같은 규칙 체계를 쓴다. 루트의 `AGENTS.md`와 `CLAUDE.md`도 규칙으로 함께 적용한다 [S14].
- **`.cursor/rules` 형식** [S12]
  - 확장자는 반드시 `.mdc`다. 일반 `.md` 파일은 무시한다.
  - 하위 폴더로 정리할 수 있다(`frontend/components.mdc`).
  - 현행 문서에는 폴더형 `RULE.md` 형식이 없다. 문서에서 "RULE.md"라는 문자열을 검색해도 나오지 않는다.
- **frontmatter와 규칙 종류** [S12]

  | `alwaysApply` | `description` | `globs` | 동작(UI 이름) |
  | --- | --- | --- | --- |
  | `true` | 무시 | 무시 | 모든 대화에 포함(Always Apply) |
  | `false` | — | 지정 | 일치하는 파일이 컨텍스트에 있으면 자동 첨부(Apply to Specific Files, 옛 이름 Auto Attached) |
  | `false` | 지정 | 없음 | Agent가 description을 보고 판단해 포함(Apply Intelligently, 옛 이름 Agent Requested) |
  | `false` | 없음 | 없음 | `@규칙명`으로 언급할 때만 포함(Apply Manually) |

  - `alwaysApply`는 boolean이다.
  - `globs`는 쉼표로 구분한 문자열이다(예: `docs/**/*.md, docs/**/*.mdx`).
  - `description`은 문자열이다.
- **파일 참조**: 규칙 본문의 `@file`은 위치만 알려준다. 내용을 프롬프트에 펼치지 않고 Agent가 필요할 때 도구로 읽는다. 편집기, CLI, Cloud Agents 모두 같다 [S12].
- **권장 사항**: 규칙은 500줄 이하로 쓴다 [S12]. `.cursorrules`는 레거시이며 앞으로 지원이 중단될 예정이다 [S13].

## 권장 구성 (파일 트리 + 각 연결 파일의 최소 내용)

```text
AGENTS.md                  # 단일 원본. 모든 규칙은 여기에만 쓴다(@import 금지, 32 KiB 미만 유지)
CLAUDE.md                  # Claude Code 연결: 1행 `@AGENTS.md`
.gemini/settings.json      # Gemini CLI 연결: context.fileName
.cursor/rules/
  server.mdc               # 선택: glob 범위 포인터 규칙(내용 복사 금지)
  client-jvm.mdc           # 선택
server/
  AGENTS.md                # 선택: 언어별 규칙
  CLAUDE.md                # server/AGENTS.md를 두면 필수: `@AGENTS.md` 한 줄
client-jvm/
  AGENTS.md                # 선택
  CLAUDE.md                # client-jvm/AGENTS.md를 두면 필수: `@AGENTS.md` 한 줄
.gitignore                 # CLAUDE.local.md, .claude/settings.local.json, AGENTS.override.md
```

**`CLAUDE.md`** (루트. 1행은 반드시 아래 import로 둔다)

```markdown
@AGENTS.md

<!-- 공통 규칙은 AGENTS.md에만 쓴다. 이 파일에는 Claude Code 전용 내용만 최소로 둔다(Cursor도 이 파일을 항상 읽는다). -->
```

- HTML 주석은 Claude Code가 컨텍스트에 넣기 전에 제거한다 [S1]. Cursor에서 주석을 어떻게 처리하는지는 확인하지 못했다.
- Claude 전용 지시가 꼭 필요하면 `.claude/rules/*.md`에 두는 편이 낫다. `CLAUDE.md`에 쓰면 Cursor에도 들어가기 때문이다. Cursor는 `.claude/rules/`를 읽는다고 문서화하지 않았다(추정).
- `.claude/CLAUDE.md`를 따로 만들지 않는다. 루트 `CLAUDE.md`와 함께 로드된다.

**`server/CLAUDE.md`, `client-jvm/CLAUDE.md`** (하위 `AGENTS.md`를 둘 때만)

```markdown
@AGENTS.md
```

- 상대 경로는 파일 자신을 기준으로 해석하므로 같은 디렉터리의 `AGENTS.md`를 가리킨다. Claude가 그 디렉터리의 파일을 다룰 때 로드된다 [S1].

**`.gemini/settings.json`**

```json
{
  "context": {
    "fileName": ["AGENTS.md", "GEMINI.md"]
  }
}
```

- `GEMINI.md` 파일은 만들지 않는다. 목록에 넣는 이유는 버전과 관계없이 기본 파일명을 함께 유지하기 위해서다.
- 나중에 Gemini 전용 내용이 필요해 `GEMINI.md`를 만들더라도 `@AGENTS.md` import는 넣지 않는다. 이미 `AGENTS.md`를 직접 읽으므로 중복된다(추정).

**`.cursor/rules/server.mdc`** (선택. Cursor는 `AGENTS.md`를 기본으로 읽으므로 공통 규칙은 넣지 않는다)

```markdown
---
description: server/ 코드를 작성하거나 수정할 때 적용하는 규칙
globs: server/**
alwaysApply: false
---

- `server/` 아래 코드를 바꾸기 전에 `docs/conventions/` 아래의 해당 언어 문서를 읽는다.
```

- `client-jvm.mdc`는 `globs: client-jvm/**/*.java`처럼 쓴다.
- glob은 디렉터리 이름으로 시작하게 쓴다. YAML에서 맨 앞의 `*`는 alias 기호로 해석될 수 있다(추정).
- `alwaysApply: true` 규칙에 `AGENTS.md` 내용을 복사하지 않는다. Cursor가 `AGENTS.md`를 이미 읽으므로 같은 내용이 두 번 들어간다.

**Codex CLI**: 연결 파일이 필요 없다. 루트에서 실행하면 하위 `AGENTS.md`를 미리 읽지 않는다. 언어별 규칙이 중요한 작업은 해당 디렉터리에서 실행하거나, 루트 `AGENTS.md`에 하위 `AGENTS.md`의 경로를 적어 둔다.

**`AGENTS.md` 작성 규칙(연결 방식 때문에 생기는 제약)**

- `@path` import를 쓰지 않는다. 다른 문서는 `docs/conventions/...` 같은 일반 경로나 링크로 참조한다. Codex와 Cursor는 import를 펼치지 않는다.
- `@Override`, `@Test`처럼 `@`로 시작하는 단어는 항상 백틱이나 코드 블록 안에 쓴다. 그러지 않으면 Claude Code와 Gemini CLI가 import로 해석할 수 있다 [S1][S8].
- 루트 `AGENTS.md`와 하위 `AGENTS.md`를 합친 크기를 32 KiB 미만으로 유지한다(Codex 기본 한도) [S4].

## 작업 원칙 출처 (andrej-karpathy-skills) — 커밋, 라이선스, 원문 4원칙

- 저장소: https://github.com/forrestchang/andrej-karpathy-skills (읽기 전용으로 clone해 확인, 2026-10-23)
- 커밋: `2c606141936f1eeef17fa3043a72095b4765b9c2`
  - 작성 시각 2026-04-20 18:05:04 +0800, 메시지 "Sync Chinese README with English version (add Cursor section) (#95)"
  - 확인 시점에 `main`의 최신 커밋이다.
- `CLAUDE.md`는 커밋 `8462496b34419f20b32778610571ac723e91f94c`(Jiayuan Zhang, GitHub `forrestchang`, 2026-01-27)에서 추가된 뒤 `2c606141`까지 바뀌지 않았다.
- 라이선스
  - 저장소 어느 브랜치에도 **LICENSE 파일이 없다**.
  - MIT라는 표시는 세 곳에만 있다: `README.md` 끝의 "## License / MIT", `.claude-plugin/plugin.json`의 `"license": "MIT"`, `skills/karpathy-guidelines/SKILL.md` frontmatter의 `license: MIT`.
  - **저작권 줄(Copyright ...)은 저장소 어디에도 없다.**
- MIT 조건 [S16]: "The above copyright notice and this permission notice shall be included in all copies or substantial portions of the Software."
  - 발췌하거나 번역할 때는 저작권 표시와 MIT 허가 문구 전문을 함께 둔다.
  - 원본에 저작권 줄이 없으므로 저작권자 표기는 커밋 작성자를 기준으로 쓴 추정이다. 원저자에게 LICENSE 파일 추가를 요청하는 것도 방법이다.

권장 출처 표기(예: `docs/conventions/working-principles.md` 끝):

```text
Portions adapted from forrestchang/andrej-karpathy-skills, CLAUDE.md
(https://github.com/forrestchang/andrej-karpathy-skills, commit 2c606141936f1eeef17fa3043a72095b4765b9c2).
Copyright (c) 2026 Jiayuan Zhang (forrestchang)  <- 원 저장소에 저작권 줄이 없어 커밋 작성자 기준으로 표기(추정)
Licensed under the MIT License:

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

`2c606141:CLAUDE.md` 원문의 4원칙은 아래와 같다(제목, 요지, 목록. 영어 원문 그대로).

```text
## 1. Think Before Coding

**Don't assume. Don't hide confusion. Surface tradeoffs.**

Before implementing:
- State your assumptions explicitly. If uncertain, ask.
- If multiple interpretations exist, present them - don't pick silently.
- If a simpler approach exists, say so. Push back when warranted.
- If something is unclear, stop. Name what's confusing. Ask.

## 2. Simplicity First

**Minimum code that solves the problem. Nothing speculative.**

- No features beyond what was asked.
- No abstractions for single-use code.
- No "flexibility" or "configurability" that wasn't requested.
- No error handling for impossible scenarios.
- If you write 200 lines and it could be 50, rewrite it.

Ask yourself: "Would a senior engineer say this is overcomplicated?" If yes, simplify.

## 3. Surgical Changes

**Touch only what you must. Clean up only your own mess.**

When editing existing code:
- Don't "improve" adjacent code, comments, or formatting.
- Don't refactor things that aren't broken.
- Match existing style, even if you'd do it differently.
- If you notice unrelated dead code, mention it - don't delete it.

When your changes create orphans:
- Remove imports/variables/functions that YOUR changes made unused.
- Don't remove pre-existing dead code unless asked.

The test: Every changed line should trace directly to the user's request.

## 4. Goal-Driven Execution

**Define success criteria. Loop until verified.**

Transform tasks into verifiable goals:
- "Add validation" → "Write tests for invalid inputs, then make them pass"
- "Fix the bug" → "Write a test that reproduces it, then make it pass"
- "Refactor X" → "Ensure tests pass before and after"

For multi-step tasks, state a brief plan:
1. [Step] → verify: [check]
2. [Step] → verify: [check]
3. [Step] → verify: [check]

Strong success criteria let you loop independently. Weak criteria ("make it work") require constant clarification.
```

- 원문 도입부에는 "**Tradeoff:** These guidelines bias toward caution over speed. For trivial tasks, use judgment."가 있다.
- 4번의 계획 예시는 원문에서 코드 블록으로 감싸져 있다.

## 미확인 항목

- Claude Code: 하위 디렉터리 `CLAUDE.md`를 Cursor가 루트 `CLAUDE.md`처럼 읽는지는 확인하지 못했다. 문서는 "AGENTS.md와 같은 방식"이라고만 한다(추정: 읽음).
- Codex CLI: 신뢰되지 않은 프로젝트에서 프로젝트 `AGENTS.md`를 건너뛰는 동작은 소스(`f3a90545`)로만 확인했다. 공식 문서에는 없다. cwd 아래 하위 `AGENTS.md`를 실제로 따르는지는 모델의 판단에 달려 있다.
- Gemini CLI
  - `context.fileName`이 기본값을 대체하는지 추가하는지는 문서에 없다. main 소스상으로는 추가한다. 릴리스 버전별 차이는 확인하지 못했다.
  - 하위 디렉터리를 시작할 때 탐색하는지, JIT로 탐색하는지는 문서끼리 다르다.
  - Trusted Folders를 켠 상태의 신뢰되지 않은 폴더에서 `GEMINI.md` 자체를 읽는지 확인하지 못했다.
- Antigravity CLI: `.gemini/settings.json`의 `context.fileName`을 따르는지, `@` import를 지원하는지, 하위 디렉터리를 어떻게 다루는지 확인하지 못했다.
- Cursor
  - 공식 문서에 날짜가 없다.
  - 폴더형 `RULE.md` 형식은 현행 문서에 없다. 과거 버전에 있었는지는 확인하지 못했다.
  - frontmatter를 엄격한 YAML로 파싱하는지(맨 앞 `*` 처리) 확인하지 못했다.
  - `CLAUDE.md` 안의 HTML 주석을 어떻게 처리하는지 확인하지 못했다.
- andrej-karpathy-skills: 저작권자 표기. 원본에 저작권 줄이 없어서 위 표기는 커밋 작성자를 기준으로 한 추정이다. GitHub API 호출 한도에 걸려 GitHub의 license 자동 감지 결과는 조회하지 못했다. LICENSE 파일이 없으므로 감지되지 않을 것으로 추정한다.

## 출처

- [S1] Claude Code, "How Claude remembers your project" — https://code.claude.com/docs/en/memory (확인 2026-10-23)
- [S2] Claude Code, "Settings files and precedence" — https://code.claude.com/docs/en/settings (확인 2026-10-23)
- [S3] Claude Code, "Explore the .claude directory" — https://code.claude.com/docs/en/claude-directory (확인 2026-10-23)
- [S4] OpenAI Codex, AGENTS.md 가이드 — https://learn.chatgpt.com/docs/agent-configuration/agents-md (https://developers.openai.com/codex/guides/agents-md 에서 308 리다이렉트, 페이지 날짜 없음, 확인 2026-10-23)
- [S5] OpenAI Codex, Configuration reference — https://learn.chatgpt.com/docs/config-file/config-reference (https://developers.openai.com/codex/config-reference 에서 리다이렉트, 확인 2026-10-23)
- [S6] openai/codex 소스 `f3a90545aed3188ee1e5c119ec89a742de399cdc`(2026-10-23) — `codex-rs/core/src/agents_md.rs`, `codex-rs/codex-home/src/instructions/mod.rs`, `codex-rs/models-manager/prompt.md`, `docs/agents_md.md` — https://github.com/openai/codex (확인 2026-10-23)
- [S7] Gemini CLI, "Provide context with GEMINI.md files" — https://geminicli.com/docs/cli/gemini-md/ (Last updated Jun 18, 2026, 확인 2026-10-23)
- [S8] google-gemini/gemini-cli 문서 `a713410aba5d594594e20d5c822e3aab8f822089`(2026-10-22) — `docs/reference/configuration.md`, `docs/reference/memport.md`, `docs/cli/trusted-folders.md` — https://github.com/google-gemini/gemini-cli (확인 2026-10-23)
- [S9] google-gemini/gemini-cli 소스 `a713410a` — `packages/core/src/tools/memoryTool.ts`, `packages/cli/src/config/config.ts`, `packages/core/src/utils/memoryDiscovery.ts`, `packages/core/src/context/memoryContextManager.ts`, `packages/core/src/tools/jit-context.ts` (확인 2026-10-23)
- [S10] Google Developers Blog, "An important update: transitioning Gemini CLI to Antigravity CLI"(2026-05-19) — https://developers.googleblog.com/an-important-update-transitioning-gemini-cli-to-antigravity-cli, 및 gemini-cli `README.md` 상단 공지 (확인 2026-10-23)
- [S11] Google Antigravity, "Migration - Antigravity CLI" — https://antigravity.google/docs/cli/gcli-migration (페이지 날짜 없음, 확인 2026-10-23)
- [S12] Cursor Docs, "Rules" — https://cursor.com/docs/rules (https://cursor.com/docs/context/rules, 마크다운 원문 https://cursor.com/docs/rules.md, 페이지 날짜 없음, 확인 2026-10-23)
- [S13] Cursor Help, "Rules" — https://cursor.com/help/customization/rules (확인 2026-10-23)
- [S14] Cursor Docs, "Using Agent in CLI" — https://cursor.com/docs/cli/using (확인 2026-10-23)
- [S15] forrestchang/andrej-karpathy-skills, 커밋 `2c606141936f1eeef17fa3043a72095b4765b9c2` — https://github.com/forrestchang/andrej-karpathy-skills/tree/2c606141936f1eeef17fa3043a72095b4765b9c2 (로컬 clone으로 확인 2026-10-23)
- [S16] Open Source Initiative, "The MIT License" — https://opensource.org/license/mit (확인 2026-10-23)
