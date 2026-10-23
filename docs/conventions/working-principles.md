# 작업 원칙

이 저장소에서 일하는 사람과 AI 에이전트가 따르는 네 가지 원칙이다. [andrej-karpathy-skills](https://github.com/forrestchang/andrej-karpathy-skills)의 `CLAUDE.md`를 발췌·번역하고 이 프로젝트에 맞게 예를 덧붙였다(출처와 라이선스는 문서 끝).

> 이 원칙은 속도보다 신중함 쪽으로 기울어 있다. 사소한 작업에는 판단껏 적용한다.

## 1. 코딩 전에 생각한다

**가정하지 않는다. 혼란을 숨기지 않는다. 트레이드오프를 드러낸다.**

구현하기 전에:

- 가정을 명시한다. 불확실하면 묻는다.
- 해석이 여러 개면 조용히 하나를 고르지 말고 제시한다.
- 더 단순한 방법이 있으면 말한다. 필요하면 반대 의견을 낸다.
- 불명확하면 멈추고, 무엇이 헷갈리는지 밝히고, 묻는다.

이 프로젝트에서: 확인할 수 없는 사실(라이브러리 버전, 도구 동작, 지원 범위)은 추측해서 쓰지 않고 "미확인"으로 남긴다. 결정이 필요하면 ADR을 `proposed`로 쓰고 사람에게 넘긴다.

## 2. 단순함이 먼저다

**문제를 푸는 최소한의 코드. 추측성 코드는 쓰지 않는다.**

- 요청받지 않은 기능을 넣지 않는다.
- 한 번만 쓰는 코드를 위한 추상화를 만들지 않는다.
- 요청받지 않은 "유연성"이나 "설정 가능성"을 넣지 않는다.
- 일어날 수 없는 상황에 대한 오류 처리를 넣지 않는다.
- 200줄로 썼는데 50줄로 될 수 있으면 다시 쓴다.

스스로 묻는다: "시니어 엔지니어가 이걸 보고 과하다고 할까?" 그렇다면 단순하게 고친다.

이 프로젝트에서: 측정 근거 없는 최적화(다중 픽셀 포맷, 세션 캐시, 공유 메모리 등)는 벤치마크로 필요가 확인될 때까지 넣지 않는다.

## 3. 외과적으로 변경한다

**필요한 것만 건드린다. 자기가 만든 흔적만 정리한다.**

기존 코드를 고칠 때:

- 인접한 코드, 주석, 포맷을 "개선"하지 않는다.
- 고장 나지 않은 것을 리팩터링하지 않는다.
- 내 취향과 달라도 기존 스타일을 따른다.
- 관련 없는 죽은 코드를 발견하면 언급만 하고 지우지 않는다.

내 변경으로 쓰이지 않게 된 것이 생기면:

- 내 변경 때문에 안 쓰이게 된 import/변수/함수만 지운다.
- 요청이 없으면 원래 있던 죽은 코드는 지우지 않는다.

기준: 바뀐 모든 줄이 요청으로 직접 추적되어야 한다.

## 4. 목표 기반으로 실행한다

**성공 기준을 정한다. 검증될 때까지 반복한다.**

작업을 검증 가능한 목표로 바꾼다.

- "검증 추가" → "잘못된 입력에 대한 테스트를 쓰고 통과시키기"
- "버그 수정" → "버그를 재현하는 테스트를 쓰고 통과시키기"
- "X 리팩터링" → "리팩터링 전후로 테스트가 통과하는지 확인하기"

여러 단계 작업은 짧은 계획을 먼저 적는다.

```
1. [단계] → 검증: [확인 방법]
2. [단계] → 검증: [확인 방법]
3. [단계] → 검증: [확인 방법]
```

성공 기준이 분명하면 혼자서 반복할 수 있다. 기준이 약하면("되게 만들어") 계속 확인을 받아야 한다.

---

## 출처와 라이선스

- 원본: [forrestchang/andrej-karpathy-skills](https://github.com/forrestchang/andrej-karpathy-skills) `CLAUDE.md`, 커밋 `2c606141936f1eeef17fa3043a72095b4765b9c2`(2026-04-20). `CLAUDE.md`는 커밋 `8462496b`(2026-01-27, Jiayuan Zhang)에서 추가된 뒤 이 커밋까지 바뀌지 않았다.
- 라이선스: MIT. 원 저장소는 README, `.claude-plugin/plugin.json`, `skills/karpathy-guidelines/SKILL.md`에 MIT라고 표기하지만 LICENSE 파일과 저작권 줄은 없다(확인 2026-10-23, [research/0002](../research/0002-agent-glue.md)).
- 아래 저작권 줄은 원 저장소에 없어서 커밋 작성자를 기준으로 적은 것이다(추정).

```text
Portions adapted from forrestchang/andrej-karpathy-skills, CLAUDE.md
(https://github.com/forrestchang/andrej-karpathy-skills, commit 2c606141936f1eeef17fa3043a72095b4765b9c2).

Copyright (c) 2026 Jiayuan Zhang (forrestchang)

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
