# 0010. 서버 입력 경로 퍼징 결과

- 상태: 초안
- 작성일/실행일: 2026-10-29
- 관련: [docs/roadmap.md](../roadmap.md) Phase 5-1, [ADR-0017](../adr/0017-output-volume-limits.md)(출력량 상한, proposed)
- 코드: [server/fuzz/](../../server/fuzz/)(대상, Dockerfile, 실행 스크립트)

## 요약

- 서버의 세 입력 경로(GetFeatures, Decode, DecodeAnimation)를 libFuzzer로 대상 2개당 30분씩 돌렸다.
- **실패 입력은 0건이다.** 메모리 오류(ASan), 메모리 초과(OOM), 시간 초과가 모두 없었다.
- 계측 범위:
  - Rust 코드(이 서버의 `unsafe` FFI 경계 포함)와 libwebp C 코드를 함께 ASan과 커버리지로 계측했다.
  - C 코드 계측은 확인했다: libwebp 정적 라이브러리에 ASan 보고 호출 510개, 커버리지 카운터 847개가 들어 있다.
- 실패 0건은 "이 시간과 이 씨앗으로는 못 찾았다"는 뜻이지 결함이 없다는 증명이 아니다(아래 "한계").

## 대상

서비스 코드(`server/src/service.rs`)가 libwebp 래퍼(`server/src/webp.rs`)를 부르는 순서를 그대로 따른다. gRPC 메시지 조립(`collect_input`)은 `unsafe`가 없는 Rust 코드라 대상에서 뺐다.

| 대상 | 따라 하는 경로 | 부르는 함수 |
|---|---|---|
| `get_features` | GetFeatures | `container_info`(최대 1,000프레임) → `metadata_chunks`(최대 2 MiB) |
| `decode` | Decode, DecodeAnimation | `features` → 픽셀 상한 검사 → 정지 이미지는 `decode_still_rgba`, 애니메이션은 `container_info` → `AnimDecoder`로 모든 프레임 합성 |

`decode`의 상한은 서버 기본값보다 낮췄다: 캔버스 4096²(기본 8192²), 프레임 64(기본 1,000). libFuzzer 기본 메모리 한도(2 GiB) 안에서 빠르게 돌리기 위해서다. 상한 검사 코드 경로는 같다. 프레임 수 × 캔버스 크기 문제(출력량 폭탄)는 ADR-0017에서 다룬다.

## 환경과 실행

| 항목 | 값 |
|---|---|
| 이미지 | `server/fuzz/Dockerfile`: `rustlang/rust:nightly-trixie`(digest 고정) + cargo-fuzz 0.13.2 + clang 23(apt.llvm.org) |
| 툴체인 | `nightly-2026-10-28`(rustc 1.101.0-nightly, LLVM 23.1.4), clang 23.1.5. ASan 런타임을 rustc와 공유하려고 LLVM 주 버전을 맞췄다 |
| 계측 | Rust: cargo-fuzz 기본(`-Zsanitizer=address`, SanitizerCoverage). C(libwebp-sys가 `cc`로 컴파일): `CC=clang-23 CFLAGS="-fsanitize=address -fsanitize=fuzzer-no-link"` |
| libFuzzer 옵션 | `-fork=8 -max_total_time=1800 -max_len=262144 -rss_limit_mb=2048 -timeout=20` |
| 격리 | 퍼징은 `--network none` 컨테이너에서 했다. 빌드만 네트워크를 썼다. CPU는 E코어에 고정했다(`decode` 16–23, `get_features` 24–31). 두 대상은 동시에 실행했다 |
| 씨앗 | `samples/generated`, `samples/malformed`, `samples/conformance`(libwebp-test-data 153개) |
| 명령 | `server/fuzz/run.sh decode 1800 8 16-23`, `server/fuzz/run.sh get_features 1800 8 24-31` |

## 결과 (2026-10-29 02:22 UTC 시작, 각 30분)

| 대상 | 시작 씨앗 | 실행 횟수 | 커버리지(edge) 시작 → 끝 | 코퍼스 끝 | OOM / 시간 초과 / 실패 |
|---|---|---|---|---|---|
| `decode` | 662 | 4,748,659 | 2,211 → 2,355 | 1,253 | 0 / 0 / 0 |
| `get_features` | 62 | 328,538,066 | 343 → 408 | 282 | 0 / 0 / 0 |

- `decode`의 시작 씨앗 662개에는 같은 날 60초 시험 실행이 남긴 코퍼스가 들어 있다.
- 커버리지는 30분 끝 무렵 거의 늘지 않았다. `decode`는 마지막 2분 동안 2,354 → 2,355였다. 더 늘리려면 실행 시간보다 씨앗 다양화(애니메이션·메타데이터 변형)가 효과가 클 것으로 본다(추정).
- 늘어난 코퍼스(`server/fuzz/corpus/`)는 커밋하지 않는다. 다음 실행의 씨앗으로 쓴다.

## 한계

- 실행 시간이 대상당 30분(8 fork)뿐이다. 장시간·연속 퍼징은 하지 않았다.
- libwebp 자체는 OSS-Fuzz에서 libFuzzer로 계속 퍼징된다(ASan·UBSan·MSan, [google/oss-fuzz projects/libwebp](https://github.com/google/oss-fuzz/blob/master/projects/libwebp/project.yaml), 2026-10-29 확인). 이 퍼저가 더하는 가치는 이 서버의 래퍼(`unsafe` FFI, 상한 검사, 애니메이션 반복)와 그 사용 방식이다.
- UBSan, MSan은 쓰지 않았다(ASan만).
- 파일별·함수별 커버리지 보고서(`cargo fuzz coverage`)는 만들지 않았다. 어떤 libwebp 함수가 덮였는지는 미확인이다.
- 인코딩 경로(Encode, EncodeAnimation)는 대상이 아니다. 로드맵 완료 기준이 입력 경로(디코딩 쪽)만 정했고, 인코딩 입력은 크기가 정해진 픽셀 배열이라 파서가 없다.

## 재현

```sh
server/fuzz/run.sh decode 1800 8 16-23
server/fuzz/run.sh get_features 1800 8 24-31
```

실패 입력이 나오면 `server/fuzz/artifacts/<대상>/`에 남는다. 재현은 같은 이미지에서 `cargo fuzz run <대상> <파일>`로 한다. 고칠 때는 그 입력을 서버 테스트의 재현 테스트로 옮긴다(AGENTS.md "버그 수정은 재현 테스트를 먼저").
