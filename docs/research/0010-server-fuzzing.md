# 0010. 서버 입력 경로 퍼징 결과

- 상태: 초안
- 작성일/실행일: 2026-10-29, 인코딩 경로·커버리지 2026-10-30
- 관련: [docs/roadmap.md](../roadmap.md) Phase 5-1, [ADR-0017](../adr/0017-output-volume-limits.md)(작업량 상한, accepted)
- 코드: [server/fuzz/](../../server/fuzz/)(대상, Dockerfile, 실행 스크립트)

## 요약

- 서버의 세 입력 경로(GetFeatures, Decode, DecodeAnimation)를 libFuzzer로 대상 2개당 30분씩 돌렸다.
- **실패 입력은 0건이다.** 메모리 오류(ASan), 메모리 초과(OOM), 시간 초과가 모두 없었다.
- 계측 범위:
  - Rust 코드(이 서버의 `unsafe` FFI 경계 포함)와 libwebp C 코드를 함께 ASan과 커버리지로 계측했다.
  - C 코드 계측은 확인했다: libwebp 정적 라이브러리에 ASan 보고 호출 510개, 커버리지 카운터 847개가 들어 있다.
- 실패 0건은 "이 시간과 이 씨앗으로는 못 찾았다"는 뜻이지 결함이 없다는 증명이 아니다(아래 "한계").
- **2026-10-30 보강**: 인코딩 경로 대상 2개(`encode`, `encode_animation`)를 더해 30분씩 돌렸다. 실패 0건이다. 네 대상의 코퍼스로 libwebp까지 포함한 커버리지 보고서를 만들었다(아래 "인코딩 경로와 커버리지").

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

## 인코딩 경로와 커버리지 (2026-10-30)

### 대상

| 대상 | 따라 하는 경로 | 입력에서 만드는 값 | 결과 검사(성질) |
|---|---|---|---|
| `encode` | Encode | 크기 1–64², lossless 여부, quality(아무 f32 비트: NaN·무한대·음수 포함), method(0–255), ICC/EXIF/XMP 페이로드(각 0–255바이트), 픽셀 | lossless면 다시 디코딩한 픽셀이 입력과 같다(ADR-0004). 넣은 메타데이터를 같은 바이트로 다시 읽는다 |
| `encode_animation` | EncodeAnimation | 캔버스 1–32², 프레임 1–8개, 프레임마다 아무 u32 표시 시간(누적 넘침 포함), 반복 횟수·배경색(아무 u32), quality 비트, XMP | 다시 읽은 프레임 수가 넣은 수 이하이고(같은 프레임은 합쳐진다) 모든 프레임이 합성된다 |

- 클라이언트가 보낼 수 있는 값을 그대로 흉내 냈다. 서버는 quality를 libwebp `WebPValidateConfig`에만 맡기므로 NaN 같은 값도 libwebp까지 간다.
- 인코딩 대상은 WebP 파일을 입력으로 받지 않으므로 씨앗 없이 시작했다(같은 날 60초 시험 실행의 코퍼스만 있다).

### 결과 (각 30분, `-fork=8`, E코어 16–31)

| 대상 | 시작(UTC) | 실행 횟수 | 커버리지(edge) 시작 → 끝 | 코퍼스 끝 | OOM / 시간 초과 / 실패 |
|---|---|---|---|---|---|
| `encode` | 06:40 | 3,014,651 | 5,759 → 6,123 | 2,690 | 0 / 0 / 0 |
| `encode_animation` | 07:10 | 5,926,900 | 5,309 → 6,382 | 4,339 | 0 / 0 / 0 |

- 두 성질 검사(lossless 픽셀 일치, 메타데이터 왕복, 애니메이션 다시 읽기)도 한 번도 실패하지 않았다.
- 재현성 주의: 두 실행의 clang은 apt.llvm.org 스냅샷 빌드가 다르다(23.1.5, 2026-10-27판과 2026-10-28판). 두 번째 실행 전에 이미지에 커버리지 도구 층을 더하면서 clang 층도 다시 받았다. Dockerfile은 clang 패키지 판을 고정하지 않는다.

### 커버리지 (네 대상의 코퍼스, `server/fuzz/coverage.sh`)

Rust와 libwebp C를 함께 소스 기반 커버리지(`-C instrument-coverage`, clang `-fprofile-instr-generate -fcoverage-mapping`)로 계측하고 코퍼스를 한 번씩 다시 돌렸다. 디코딩 대상에는 씨앗(samples)도 함께 돌렸다.

| 범위 | 줄 커버리지 | 함수 커버리지 |
|---|---|---|
| libwebp `src/dec`(디코더) | 70.4% (2,865/4,071) | 71.6% (131/183) |
| libwebp `src/demux` | 89.6% (885/988) | 86.8% (59/68) |
| libwebp `src/enc`(인코더) | 76.9% (6,927/9,004) | 82.1% (372/453) |
| libwebp `src/mux` | 80.7% (1,902/2,356) | 89.9% (124/138) |
| libwebp `src/utils` | 67.6% (1,375/2,033) | 76.4% (110/144) |
| libwebp `src/dsp` | 58.5% (4,345/7,422) | 57.9% (336/580) |
| libwebp `sharpyuv` | 0% | 0% |
| 서버 `src/webp.rs`(libwebp 래퍼) | 79.9% (441/552) | 68.9% (42/61) |

- **덮이지 않은 디코더 함수는 서버가 쓰지 않는 기능이다**(함수 이름으로 확인, `llvm-cov report -show-functions`).
  - 다른 출력 형식: RGB/BGR/YUV/RGBA4444 출력과 그 편의 함수(`WebPDecodeRGB`, `WebPDecodeYUVInto` 등)
  - 축척: rescaler 전부(`dsp/rescaler*.c`, `utils/rescaler_utils.c`)
  - 자르기: `WebPCheckCropDimensions`
  - 점진 디코딩의 상태 저장·복원(`vp8l_dec.c` `SaveState`/`RestoreState`)
  - alpha 디더링(`utils/quant_levels_dec_utils.c`)
  - 서버는 `WebPDecode`로 RGBA를 받는다.
- **alpha 디코더(`dec/alpha_dec.c`)는 함수가 모두 실행됐다.**
- `dsp`가 낮은 것은 같은 함수의 C 구현과 SIMD 구현이 함께 들어 있기 때문이다. SSE2가 있으면 C 구현 상당수는 쓰이지 않는다(예: `dsp/dec.c` 36.6%, `dsp/dec_sse2.c` 100%). SSE4.1·AVX2 파일은 이 빌드에서 빈 파일이라 보고서에 나오지 않는다(libwebp-sys 기본 빌드는 SSE2만, research/0013).
- `sharpyuv`는 인코더의 `use_sharp_yuv` 옵션에서만 쓴다. 서버 API에 그 옵션이 없다.
- 서버의 다른 파일(`service.rs`, `admission.rs` 등)은 퍼징 대상이 아니라 0%로 나온다. 그 코드는 서버 테스트(단위·통합)가 다룬다.

## 한계

- 실행 시간이 대상당 30분(8 fork)뿐이다. 장시간·연속 퍼징은 하지 않았다. 연속 퍼징은 CI(로드맵 "첫 배포 전 결정")가 정해진 뒤에 붙일 수 있다.
- libwebp 자체는 OSS-Fuzz에서 libFuzzer로 계속 퍼징된다(ASan·UBSan·MSan, [google/oss-fuzz projects/libwebp](https://github.com/google/oss-fuzz/blob/master/projects/libwebp/project.yaml), 2026-10-29 확인). 이 퍼저가 더하는 가치는 이 서버의 래퍼(`unsafe` FFI, 상한 검사, 애니메이션 반복)와 그 사용 방식이다.
- UBSan, MSan은 쓰지 않았다(ASan만).
- 커버리지 보고서는 2026-10-30에 만들었다(위). 커버리지는 코퍼스를 다시 돌린 결과라, 퍼징 중에 한 번 지나갔지만 코퍼스에 남지 않은 경로는 빠질 수 있다.
- 인코딩 대상은 크기를 작게 잡았다(정지 64², 애니메이션 32² × 8프레임). 큰 캔버스에서만 나는 문제(메모리, 정수 넘침)는 다루지 못한다. 그 크기는 상한과 메모리 예산(ADR-0016, 0017)이 묶는다.

## 재현

```sh
server/fuzz/run.sh decode 1800 8 16-23
server/fuzz/run.sh get_features 1800 8 24-31
server/fuzz/run.sh encode 1800 8
server/fuzz/run.sh encode_animation 1800 8
server/fuzz/coverage.sh                      # 코퍼스가 있어야 한다
```

실패 입력이 나오면 `server/fuzz/artifacts/<대상>/`에 남는다. 재현은 같은 이미지에서 `cargo fuzz run <대상> <파일>`로 한다. 고칠 때는 그 입력을 서버 테스트의 재현 테스트로 옮긴다(AGENTS.md "버그 수정은 재현 테스트를 먼저").
