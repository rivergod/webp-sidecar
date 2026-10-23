# 0007. AI 에이전트 규칙은 AGENTS.md 단일 원본 + 도구별 얇은 연결 파일로 둔다

- 상태: proposed
- 날짜: 2026-10-23
- 결정자: -
- 관련 문서: [docs/research/0002-agent-glue.md](../research/0002-agent-glue.md)

## 맥락

Claude Code, Codex CLI, Gemini CLI, Cursor가 같은 규칙을 읽어야 한다. 도구마다 기본으로 읽는 파일과 import 문법이 다르다([research/0002](../research/0002-agent-glue.md), 확인 2026-10-23).

- Codex CLI와 Cursor는 `AGENTS.md`를 기본으로 읽는다.
- Claude Code는 `CLAUDE.md`를 읽는다. 최신 버전은 `CLAUDE.md`가 없을 때만 `AGENTS.md`를 직접 읽고, 공식 문서는 `CLAUDE.md`에 `@AGENTS.md` import를 두는 방식을 권한다.
- Gemini CLI는 기본으로 `GEMINI.md`를 읽고, `.gemini/settings.json`의 `context.fileName`으로 다른 파일을 읽게 할 수 있다.
- Cursor는 루트 `CLAUDE.md`도 항상 적용한다.
- 작업자가 Windows에서도 일할 수 있다. Windows에서 심볼릭 링크는 권한·Git 설정에 따라 깨지고, Claude Code의 Edit/Write 도구는 링크를 통해 쓰지 않는다.

## 결정 동인

1. 규칙 원본은 하나여야 한다(복사본 사이의 불일치 방지).
2. Windows를 포함한 모든 작업 환경에서 동작해야 한다(심볼릭 링크 금지).
3. 도구별 파일은 최소여야 하고, 한 도구 전용 지시가 다른 도구로 새면 안 된다.
4. 각 도구의 공식 문서가 지원하는 방식이어야 한다.

## 검토한 선택지

1. **`AGENTS.md` 단일 원본 + 도구별 얇은 연결 파일(import/설정)**
2. 도구별 파일에 같은 내용을 복사하고 스크립트로 동기화
3. 심볼릭 링크(`CLAUDE.md` → `AGENTS.md` 등)
4. `CLAUDE.md`를 원본으로 하고 나머지 도구가 그것을 읽게 함

## 결정

**선택지 1을 추천한다.** 구성은 다음과 같다.

| 파일 | 내용 |
|---|---|
| `AGENTS.md` | 유일한 규칙 원본. `@` import를 쓰지 않는다. |
| `CLAUDE.md` | 첫 줄 `@AGENTS.md`. 그 외에는 Claude Code 전용 내용만 최소로(Cursor도 이 파일을 읽기 때문). |
| `.gemini/settings.json` | `{"context": {"fileName": ["AGENTS.md", "GEMINI.md"]}}`. `GEMINI.md`는 만들지 않는다. |
| `.cursor/rules/*.mdc` | 디렉터리 glob(`proto/**`, `server/**`, `client-jvm/**`) 범위로 `docs/conventions/` 문서를 가리키는 짧은 규칙. 내용을 복사하지 않는다. |
| Codex CLI | 연결 파일 없음. |
| 하위 디렉터리 | 언어별 규칙이 필요하면 `server/AGENTS.md` 등을 두고, 같은 위치에 `@AGENTS.md` 한 줄짜리 `CLAUDE.md`를 함께 둔다. |

### 결과

- 긍정: 규칙을 한 곳에서만 고친다. 심볼릭 링크 없이 Windows에서도 동작한다.
- 긍정: 각 연결 방식은 각 도구의 공식 문서가 설명하는 방식이다.
- 부정: 하위 `AGENTS.md`를 추가할 때 `CLAUDE.md`도 함께 만들어야 한다(잊기 쉬움 — `AGENTS.md`에 규칙으로 적었다).
- 부정: 도구의 동작이 바뀌면 연결 방식도 바꿔야 한다. 특히 Gemini CLI는 2026-06-18부터 일부 개인 사용자에게 Antigravity CLI로 대체됐고, Antigravity CLI가 `context.fileName`을 따르는지는 미확인이다.
- 부정: Codex CLI는 작업 디렉터리 아래의 하위 `AGENTS.md`를 자동으로 읽지 않는다.

### 확인 방법

- 각 도구에서 "이 저장소에서 ADR은 어떤 상태로 써야 하나?"를 물어 `proposed`라고 답하는지 확인한다(도구별 1회, 사람이 수행).
- 도구가 크게 업데이트되면 [research/0002](../research/0002-agent-glue.md)의 확인 날짜를 갱신한다.

## 선택지별 장단점

### 1. 단일 원본 + 얇은 연결 파일 (추천)

- 장점: 원본 하나, 링크 없음, 공식 지원 방식.
- 단점: 도구별 연결 파일 3종을 유지해야 한다.

### 2. 내용 복사 + 동기화 스크립트

- 장점: 각 도구가 자기 파일만 읽어도 된다.
- 단점: 동기화를 잊으면 규칙이 어긋난다. 스크립트와 그 실행 시점을 관리해야 한다.

### 3. 심볼릭 링크

- 장점: 파일 하나를 여러 이름으로 노출한다.
- 단점: Windows에서 관리자 권한/개발자 모드가 필요하고 `core.symlinks=false`면 한 줄짜리 텍스트 파일로 체크아웃된다. Claude Code의 Edit/Write 도구는 링크를 통해 쓰지 않는다.

### 4. `CLAUDE.md` 원본

- 장점: Claude Code와 Cursor는 바로 읽는다.
- 단점: Codex CLI는 `CLAUDE.md`를 읽지 않으므로 별도 설정이 필요하다. `AGENTS.md`가 업계 공통 이름에 더 가깝다(Codex, Cursor 기본값).

## 참고

- 도구별 확인 결과와 출처: [docs/research/0002-agent-glue.md](../research/0002-agent-glue.md)
