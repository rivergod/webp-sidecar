# 0013. 사진 alpha 디코딩 격차의 원인: libwebp 1.6.0 `DispatchAlpha_SSE2`와 musl 할당자

- 상태: 초안
- 작성일/측정일: 2026-10-30
- 관련: [research/0011](0011-phase5-performance.md)(격차 발견, ADR-0011 재판정), [ADR-0011](../adr/0011-hypothesis-criteria.md)(판정 기준), [ADR-0008](../adr/0008-libwebp-version-pinning.md)(libwebp 버전 고정), [ADR-0012](../adr/0012-server-runtime-config.md)(서버 이미지), [ADR-0018](../adr/0018-decode-output-buffer-pool.md)(출력 버퍼 풀), [ADR-0019](../adr/0019-prototype-retrospective.md)(회고, alpha 격차는 후속 조사)
- 원자료: [bench/results/2026-10-30-alpha/](../../bench/results/2026-10-30-alpha/)(`server-build.tsv`, `c-harness.tsv`, `allocator-faults.tsv`, `environment.txt`)
- 도구: `bench/libwebp-alpha/`(`run.sh`, `Dockerfile`, `alpha_decode.c`), `server/examples/decode_time.rs`

## 요약

ADR-0011에서 남은 미달 2건(사진 alpha M 0.45, L 0.47)의 원인을 찾았다. 서버 안 libwebp alpha 디코딩이 느린 이유는 둘이고, M 기준으로 거의 절반씩이다.

| 단계(사진 alpha M, 중앙값) | ms | 차이 |
|---|---|---|
| 서버 빌드: libwebp 1.6.0, musl, SSE2(지금 서버) | 5.58 | |
| 같은 코드를 glibc로 빌드 | 4.75 | −0.84(musl 몫) |
| 공식 빌드 1.6.0(glibc) | 4.79 | 서버 glibc 빌드와 같다 |
| 공식 빌드 1.6.0 + 1.5.0의 `alpha_processing_sse2.c` | 3.82 | −0.98(`DispatchAlpha_SSE2` 몫) |
| 공식 빌드 1.3.2(JNI 플러그인 내장 버전) | 3.83 | |

- **원인 1: libwebp 1.6.0의 `DispatchAlpha_SSE2` 변경(확인).**
  - upstream 커밋 [`44f91b0ddd07`](https://github.com/webmproject/libwebp/commit/44f91b0ddd07b8482c3e5926fba4745bd0969cbe)("Speed DispatchAlpha_SSE2 up", 2025-04-10)이 이 함수를 `_mm_maskmoveu_si128`(MASKMOVDQU) 저장으로 바꿨다. 이 함수는 RGB 출력에 alpha 값을 써 넣는다.
  - 1.6.0에서 이 파일 하나만 1.5.0 것으로 바꾸면 1.5.0 속도로 돌아온다(M 4.79 → 3.82 ms, L 11.30 → 8.98 ms, XL 67.5 → 54.9 ms).
  - 1.3.2, 1.4.0, 1.5.0은 서로 같다. 1.6.0에서만 느리다.
  - upstream main(`1844042d`, 2026-10-19)에도 같은 코드가 있다. 고쳐지지 않았다.
- **원인 2: musl 할당자(계측 확인, 시간 기여는 추정).**
  - alpha 디코딩은 요청마다 큰 임시 버퍼를 할당한다. musl은 이것을 매번 mmap/munmap한다.
  - 1회당 page fault가 M 1,219번, L 3,129번이다. glibc는 0번이다.
  - 늘어난 시간을 fault 수로 나누면 M과 L 모두 fault당 약 0.7 µs로 같다.
  - 출력 버퍼는 이미 풀(ADR-0018)로 재사용하므로, alpha가 없는 lossy는 musl과 glibc 차이가 2%뿐이다.
- **SIMD 수준과 libwebp-sys 빌드 방식은 원인이 아니다.**
  - AVX2 빌드와 SSE2 빌드의 차이는 2% 이하다.
  - libwebp-sys 빌드(SSE2만)와 공식 CMake 빌드(SIMD 런타임 선택)의 차이도 1% 이하다(4.75 대 4.79 ms).
- **ADR-0011 판정에 미칠 영향(추정)**: 아낀 서버 시간이 그대로 요청당 시간에서 빠진다고 보면 다음과 같다. 대 JNI 기준은 0.5다.

  | 조건 | 지금 | 원인 1만 해소 | 둘 다 해소 |
  |---|---|---|---|
  | 사진 alpha M 대 JNI | 0.45 | 0.51 | 0.57 |
  | 사진 alpha L 대 JNI | 0.47 | 0.53 | 0.60 |

- **아무것도 바꾸지 않았다.** 대응(upstream 보고, 패치, 기반 이미지 변경)은 아래 "선택지"에 적었고 결정이 필요하다.

## 배경

research/0011의 비용 분해에서 사진 alpha M의 서버 libwebp 시간(5.61 ms)이 JNI 플러그인의 전체 시간(4.02 ms)보다 길었다. 후보는 다음 넷이었다.

- libwebp 버전: JNI(darkxanter)는 1.3.2, 서버는 1.6.0
- C 라이브러리와 할당자: 서버는 musl 정적 빌드
- SIMD 수준: libwebp-sys 기본 빌드는 SSE2만
- 출력 경로: RGBA 등 출력 방식

## 측정 조건

| 항목 | 값 |
|---|---|
| CPU | Intel Core i9-14900. 단일 스레드는 CPU 12에 고정한다. `use_threads=1` 측정은 CPU 12·14를 준다 |
| 반복 | 입력마다 200회, 중앙값(최소, p90도 기록) |
| 입력 | 사진 세트 M(1024×768), L(1920×1080), XL(4000×3000)의 lossy q75와 lossy+alpha q75(research/0009 "입력", sha256은 `environment.txt`) |
| 서버 빌드(`server-build.tsv`) | `server/examples/decode_time.rs`. 서버 Decode와 같은 함수(`features` + `decode_still_rgba_into`)이고, 출력 버퍼를 재사용한다(`DECODE_TIME_REUSE=1`, 풀과 같다). libwebp-sys 0.14.4(벤더링한 libwebp 1.6.0). {musl, glibc} × {SSE2(기본), AVX2(`simd-avx2`, SSE4.1 포함)} |
| 공식 빌드(`c-harness.tsv`) | 공식 tarball(sha256 확인, WebP release signing key `6B0E6B70976DE303EDF2F601F9C3D6BDB8232B5D`의 서명 확인)을 CMake Release, 정적 라이브러리, glibc(Debian trixie), 기본 SIMD 런타임 선택으로 빌드했다. `alpha_decode.c`(`WebPDecode`). `into`는 미리 할당한 버퍼에, `alloc`은 libwebp가 할당한다 |
| 할당 계측(`allocator-faults.tsv`) | 서버 빌드(SSE2)를 반복 10회와 210회로 돌린 차이를 200으로 나눴다. minor page fault는 GNU time(`%R`), mmap/munmap은 `strace -c` |
| 커밋 | `ca19709`. `environment.txt`의 "(dirty)"는 측정 중 편집하던 문서(`docs/`, `bench/.gitignore`)다. 측정 도구 변경은 없다 |

## 결과

### 1. 서버 빌드(libwebp 1.6.0): C 라이브러리와 SIMD

중앙값 ms.

| 입력 | musl SSE2(지금) | musl AVX2 | glibc SSE2 | glibc AVX2 |
|---|---|---|---|---|
| M lossy | 2.74 | 2.83 | 2.68 | 2.66 |
| M alpha | **5.58** | 5.58 | 4.75 | 4.74 |
| L lossy | 6.32 | 6.25 | 6.23 | 6.14 |
| L alpha | **13.37** | 13.39 | 11.30 | 11.22 |
| XL lossy | 36.15 | 35.91 | 35.95 | 35.55 |
| XL alpha | **71.53** | 70.97 | 67.18 | 66.61 |

- alpha가 있으면 musl이 glibc보다 M 18%, L 18%, XL 6% 느리다. alpha가 없으면 0.5–2%다.
- AVX2는 차이가 2% 이하다(M lossy musl AVX2 2.83은 p90 3.99로 흔들린 값이다).

### 2. libwebp 버전별(공식 빌드, RGBA, 미리 할당한 버퍼)

중앙값 ms.

| 입력 | 1.3.2 | 1.4.0 | 1.5.0 | 1.6.0 | 1.6.0 + 1.5.0 `alpha_processing_sse2.c` |
|---|---|---|---|---|---|
| M lossy | 2.69 | 2.68 | 2.68 | 2.68 | 2.68 |
| M alpha | 3.83 | 3.81 | 3.81 | **4.79** | 3.82 |
| L lossy | 6.24 | 6.23 | 6.23 | 6.23 | 6.23 |
| L alpha | 9.07 | 9.00 | 9.01 | **11.30** | 8.98 |
| XL lossy | 36.09 | 35.95 | 35.97 | 36.01 | 35.99 |
| XL alpha | 55.33 | 55.27 | 55.18 | **67.51** | 54.92 |

- alpha가 없는 입력은 네 버전이 같다.
- alpha 입력은 1.6.0에서만 25%(M), 25%(L), 22%(XL) 느리다.
- 파일 하나를 바꾼 변형이 1.5.0과 같은 속도다. 차이 전부가 이 파일에서 나온다.
- 세 빌드(1.5.0, 1.6.0, 변형)의 RGBA 출력은 바이트 단위로 같다. `WebPDecodeRGBA` 결과 전체의 FNV-1a 64 해시를 비교했다(측정 이미지의 빌드 단계에서 작은 C 프로그램으로 확인, 저장소에는 넣지 않음).

  | 입력 | 해시(세 빌드 공통) |
  |---|---|
  | M alpha | `27289c0df61b8feb` |
  | L alpha | `bc79176d10a6871f` |
  | XL alpha | `7f68e2e166800dc4` |

### 3. 출력 방식별(1.3.2 대 1.6.0, 미리 할당한 버퍼)

| 출력 | M alpha 1.3.2 | M alpha 1.6.0 | 늘어난 비율 | L alpha 1.3.2 | L alpha 1.6.0 |
|---|---|---|---|---|---|
| RGBA | 3.83 | 4.79 | +25% | 9.07 | 11.30 |
| BGRA | 3.83 | 4.74 | +24% | 9.06 | 11.27 |
| ARGB | 3.83 | 4.71 | +23% | 9.02 | 11.19 |
| rgbA(premultiplied) | 4.04 | 5.26 | +30% | 9.57 | 12.42 |
| YUVA(libwebp 할당) | 3.54 | 3.53 | 0% | 8.27 | 8.22 |

- RGB 계열 출력 넷이 모두 느려졌다. 이 넷은 모두 `WebPDispatchAlpha`로 alpha를 써 넣는다.
- YUVA는 alpha 평면을 따로 받으므로 이 함수를 쓰지 않는다. 그래서 버전 차이가 없다.
- alpha 평면 디코딩(ALPH 청크) 자체는 버전 사이에 바뀌지 않았다는 뜻이다.

### 4. 할당 계측(서버 빌드, 1회당)

| 빌드 | 입력 | minor page fault | mmap | munmap |
|---|---|---|---|---|
| musl | M lossy | 29 | 2 | 2 |
| musl | M alpha | 1,219 | 12 | 12 |
| musl | L lossy | 53 | 2 | 2 |
| musl | L alpha | 3,129 | 12 | 12 |
| glibc | 네 입력 모두 | 0–1 | 0 | 0 |

- musl은 alpha 디코딩 1회마다 큰 블록 약 10개를 새로 매핑하고 해제한다. page fault 수로 보면 M 약 4.8 MiB, L 약 12.2 MiB의 새 페이지를 매번 만진다.
- glibc는 해제된 큰 블록을 다음 요청에 재사용한다(mmap 임계값을 해제 크기에 맞춰 올린다. research/0009 "할당자").
- 늘어난 시간(M 0.84 ms, L 2.07 ms)을 늘어난 fault 수로 나누면 각각 0.69 µs, 0.66 µs다. 크기가 달라도 fault당 비용이 같다. 그래서 musl 몫은 page fault(커널의 페이지 0 채우기와 매핑)로 설명된다고 본다(추정).
- 어떤 할당인지는 확인하지 않았다(미확인). 크기로 보면 alpha 평면(픽셀당 1바이트)과 alpha용 lossless 디코더의 32비트 작업 버퍼(픽셀당 4바이트)로 맞아떨어진다(추정).

### 5. 그 밖의 관찰

- **`use_threads=1`**: libwebp가 필터링을 두 번째 스레드로 넘긴다. 단건 지연이 M lossy 2.69 → 1.84 ms(−32%), XL lossy 36.1 → 21.9 ms(−39%)로 준다.
  - 서버는 `use_threads = 0`이다(`server/src/webp.rs`). 요청 하나가 스레드 하나를 쓰고, 동시 처리 2와 CPU 2가 짝이다.
  - 부하가 있을 때는 CPU를 나눠 쓰므로 처리량 이득이 작다고 본다(추정, 측정하지 않음). 바꾸지 않는다.
- **libwebp 할당 대 미리 할당한 버퍼**(glibc): XL lossy가 36.0 → 45.1 ms(+25%)다. M·L은 차이가 없다. 큰 출력 버퍼를 새로 만드는 비용이다. 서버는 풀(ADR-0018)로 이것을 피한다.

## 원인 1 상세: `DispatchAlpha_SSE2`

1.5.0과 1.6.0의 `src/dsp/alpha_processing_sse2.c` 차이는 이 함수 하나다(diff 덩어리 3개: include 추가, 함수 본문, 반환값). 다른 디코더 파일도 1.6.0에서 많이 바뀌었지만, 이 파일만 되돌려도 속도가 돌아온다(위 표 2).

- 1.5.0: 출력 픽셀 8개(32바이트)를 읽고, alpha 자리를 마스크로 지우고, alpha를 OR로 넣어 다시 쓴다(`_mm_loadu_si128` / `_mm_and_si128` / `_mm_or_si128` / `_mm_storeu_si128`).
- 1.6.0: 출력을 읽지 않는다. alpha 16개를 32비트로 넓혀 `_mm_maskmoveu_si128(a, alpha_mask, ptr)`로 각 픽셀의 alpha 바이트만 쓴다.

  ```c
  // libwebp 1.6.0 src/dsp/alpha_processing_sse2.c (BSD-3-Clause, Google Inc.)
  for (i = 0; i + 16 <= width - 1; i += 16) {
    const __m128i a0 = _mm_loadu_si128((const __m128i*)&alpha[i]);
    ...
    _mm_maskmoveu_si128(a2_lo_lo, alpha_mask, ptr + 0);
    _mm_maskmoveu_si128(a2_lo_hi, alpha_mask, ptr + 16);
    _mm_maskmoveu_si128(a2_hi_lo, alpha_mask, ptr + 32);
    _mm_maskmoveu_si128(a2_hi_hi, alpha_mask, ptr + 48);
    ...
  }
  ```

- upstream 커밋 메시지(`44f91b0ddd07`)는 이것을 속도 개선으로 소개한다.

  > On some dataset, this was taking 2.5%. 2% when switching to `_mm_maskmoveu_si128`. 1.7% when using `_mm_loadu_si128`
  >
  > Confirmed by IACA: going from throughput of 4.26 to 3.5 and then to 6.26 for twice the input.

- 이 CPU에서는 반대로 느리다. 느린 이유는 재지 않았다(perf를 쓸 수 없었다: `perf_event_paranoid=4`). 아래는 추정이다.
  - MASKMOVDQU는 non-temporal 힌트가 붙은 바이트 마스크 저장이다(Intel SDM 명령 설명, 원문은 이 문서에서 다시 확인하지 않았다: 미확인).
  - 방금 업샘플러가 캐시에 써 둔 출력 줄에 픽셀당 1바이트씩 non-temporal로 부분 저장한다. 그러면 캐시 줄을 내보내고 부분 쓰기를 메모리로 보내는 비용이 생길 수 있다.
  - IACA는 정적 처리량 분석 도구라서 이런 메모리 계층 비용을 보지 않는다.
- 다른 CPU(AMD, 다른 Intel 세대)에서 같은지는 재지 않았다(미확인).

## 선택지 (결정 필요)

이 문서는 결정하지 않는다. 결정이 나오면 ADR로 쓴다.

### 원인 1

| 안 | 내용 | 비고 |
|---|---|---|
| A. upstream 보고 | libwebp 이슈 트래커에 재현 수치(이 문서 표 2, 3)와 함께 보고한다 | 외부 공개 행위라 결정자 승인이 필요하다. 다른 CPU 측정이 있으면 보고가 강해진다 |
| B. 서버 빌드에 되돌림 패치 | 서버의 libwebp에 이 함수만 1.5.0 구현으로 되돌린다 | ADR-0008(릴리스 태그 고정, 선별 패치는 택하지 않음)과 충돌한다. 대체 ADR이 필요하다. libwebp-sys가 소스를 벤더링하므로 포크나 빌드 덮어쓰기가 필요하다. 패치 뒤 적합성 테스트와 픽셀 해시로 결과가 같은지 확인해야 한다 |
| C. 함수 포인터 교체 | 서버가 초기화 뒤 `WebPDispatchAlpha` 전역 포인터를 자체 구현으로 바꾼다 | 공개 API가 아닌 내부 심볼에 기댄다. 권하지 않는다 |
| D. 기다린다 | upstream이 고치면 다음 릴리스로 올린다 | main(2026-10-19)까지 고쳐지지 않았다. A 없이는 고쳐질 근거가 없다 |

### 원인 2

| 안 | 내용 | 비고 |
|---|---|---|
| E. glibc 기반 이미지 | 서버를 glibc로 동적 빌드해 distroless/cc 이미지에 넣는다(ADR-0012에서 검토한 대안) | ADR-0012(musl 정적 + scratch, glibc 정적 링크는 하지 않음)를 대체하는 ADR이 필요하다. 이미지가 커진다(스파이크 기준 11 MB). seccomp 프로파일은 다시 만들어 확인해야 한다 |
| F. musl 정적 빌드에 다른 malloc | 정적 바이너리의 C `malloc`을 다른 할당자(mimalloc 등)로 바꾼다 | libwebp(C)가 쓰는 `malloc`까지 바뀌어야 효과가 있다. 정적 musl에서 가능한지 확인하지 않았다(미확인) |
| G. 그대로 둔다 | 원인 1만 해소해도 추정상 M·L이 기준(0.5)을 넘는다 | 넘는 폭이 작다(0.51, 0.53) |

## 한계

- CPU 한 종류(i9-14900)에서만 쟀다.
- 원인 1의 기제(MASKMOVDQU가 왜 느린지)는 계측하지 않았다.
- musl 쪽은 fault 수와 시간이 맞아떨어진다는 것까지다. 어떤 할당인지는 보지 않았다.
- ADR-0011 영향은 추정이다. 실제 JMH 재판정(`bench/run.sh`)은 대응을 정한 뒤에 한다.

## 재현

```sh
# 사진 세트가 먼저 있어야 한다(bench/run.sh가 만든다). 약 11분.
BENCH_RESULTS=bench/results/<이름> bench/libwebp-alpha/run.sh 200

# 할당 계측(위 run.sh가 서버 빌드를 server/target/alpha-sse2/ 아래에 남긴다). 1회당 값 = (210회 − 10회) / 200
B=server/target/alpha-sse2/x86_64-unknown-linux-musl/release/examples/decode_time
F=samples/bench/photo/M_1024x768_lossy_alpha_q75.webp
for n in 10 210; do DECODE_TIME_REUSE=1 taskset -c 12 /usr/bin/time -f "%R" "$B" "$n" "$F" >/dev/null; done
for n in 10 210; do DECODE_TIME_REUSE=1 taskset -c 12 strace -f -c -e trace=mmap,munmap "$B" "$n" "$F" >/dev/null; done
```

upstream 확인(2026-10-30): `https://api.github.com/repos/webmproject/libwebp/commits?path=src/dsp/alpha_processing_sse2.c`(최근 커밋 목록), `https://raw.githubusercontent.com/webmproject/libwebp/main/src/dsp/alpha_processing_sse2.c`(main의 `_mm_maskmoveu_si128` 사용).
