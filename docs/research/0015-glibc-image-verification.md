# 0015. glibc 서버 이미지 확인(ADR-0021)

- 상태: 초안
- 작성일/측정일: 2026-11-02 – 2026-11-03
- 관련: [ADR-0021](../adr/0021-glibc-distroless-image.md)(glibc 이미지), [ADR-0011](../adr/0011-hypothesis-criteria.md)(판정 기준, 바꾸지 않음), [ADR-0016](../adr/0016-memory-budget-admission.md)(메모리 예산), [ADR-0018](../adr/0018-decode-output-buffer-pool.md)(출력 버퍼 풀), [research/0013](0013-alpha-decode-gap.md)(alpha 격차 원인), [research/0011](0011-phase5-performance.md)(Phase 5 판정), [research/0014](0014-load-soak.md)(musl 60분 부하), [research/0007](0007-server-memory-budget.md)(메모리 예산 측정)
- 측정 도구: `bench/run.sh`, `bench/soak.sh`, `server/scripts/measure-memory.sh`, 그리고 이 문서용 [bench/glibc-check/](../../bench/glibc-check/)(`rounds.sh`, `faults.sh`, `warm-worst.sh`)
- 원자료:
  - [bench/results/2026-11-02-glibc/](../../bench/results/2026-11-02-glibc/): `summary.md`, `results.csv`, `environment.txt`, `decode-time-{sse2,avx2}.tsv`(출력 버퍼 재사용), `decode-time-{sse2,avx2}-alloc.tsv`(새 할당, 아래 "측정 도구 수정"), `libc-rounds.tsv`(musl·glibc 교대 측정)
  - [bench/results/2026-11-03-glibc-faults/](../../bench/results/2026-11-03-glibc-faults/): `faults.tsv`(요청당 page fault), `environment.txt`
  - [bench/results/2026-11-02-glibc-memory/](../../bench/results/2026-11-02-glibc-memory/)`measure-memory.tsv`(ADR-0016 확인 조합)
  - [bench/results/2026-11-02-glibc-soak/](../../bench/results/2026-11-02-glibc-soak/)(60분 섞인 부하)
  - [bench/results/2026-11-03-glibc-warm/](../../bench/results/2026-11-03-glibc-warm/)(trim 없음), [bench/results/2026-11-03-glibc-trim-warm/](../../bench/results/2026-11-03-glibc-trim-warm/)(유휴 trim): 부하 뒤 유휴 반환과 최악 조합

## 요약

- ADR-0011 판정이 18개 조건 중 18개 충족이 됐다(Phase 5 musl은 16개). 남았던 사진 alpha M·L이 0.45 → 0.56, 0.47 → 0.54로 올랐다.
- 서버 안 libwebp 디코딩은 같은 세션에서 alpha M −13%, L −18%였다(research/0013과 같은 방향).
- alpha가 없는 사진도 사이드카 디코딩이 빨라졌다(lossy M +11.0%, lossless M +8.7%, lossless XL만 −0.4%). 서버 libwebp 차이(−6%–+2%)보다 크므로 대부분 libwebp 밖에서 왔다. Decode RPC 1회당 page fault가 musl 417–16,072회에서 glibc 0–19회로 줄었다(XL lossless·alpha는 약 5,900회).
- 60분 섞인 부하 처리량은 +8.4%(64.02 → 69.37 ops/s)였다.
- 격리(verify-hardening 23개, 통합 테스트, seccomp 61개)와 메모리 조합(OOM 0건, 최고치 −2%–+8%)은 musl 이미지와 같은 수준이다.
- 문제 하나를 찾아 고쳤다. glibc 기본값에서는 부하 뒤 유휴 메모리가 78–176 MiB로 남아 ADR-0018의 유휴 반환이 성립하지 않았다.
  - 고친 방법(최종 설정): 서버가 유휴로 접어들면 `malloc_trim(0)`을 부르고, 이미지에 `MALLOC_ARENA_MAX=2`를 둔다.
  - trim만으로는 스레드별 arena의 꼭대기가 남아 72 MiB였다.
  - 최종 설정에서 유휴 60초 뒤 2.3 MiB로 돌아왔다. 데운 컨테이너의 최악 조합 최고치도 705 → 605 MiB로 줄었다.
  - 대가는 섞인 부하 처리량 약 3%다(68.95 → 66.91 ops/s, 추정 범위 2–3%). 단건 디코딩에서는 차이가 측정 흔들림 안이었다.
- 서버 디코딩 시간 측정 도구가 서버와 다른 출력 방식(매번 새 할당)으로 재던 것을 고쳤다(`bench/run.sh`).

## 측정 조건

| 항목 | 값 |
|---|---|
| glibc 이미지(할당자 기본값) | `webp-imageio-server:dev` sha256 `fbdcf581…`(기본 SSE2), AVX2 빌드 `9a0b1ba6…`. 커밋 `2ad7555`의 deploy/Dockerfile. JMH, 서버 디코딩 시간, 메모리 조합, 60분 부하, page fault(musl 비교), "glibc 기본값" 데우기에 썼다 |
| glibc 이미지(유휴 trim) | `69aba94b…`, 다시 빌드한 `b8257976…`(레이어가 같다. 서버 바이너리 sha256 `bf662978…`). arena는 기본값이다. "유휴 trim" 데우기, 유휴 시험, page fault(arena 비교)에 썼다 |
| glibc 이미지(최종) | 유휴 trim + `MALLOC_ARENA_MAX=2`. 데우기와 page fault는 `b8257976…`에 환경변수만 더한 `01f53522…`로 쟀다. JMH 일부, 격리 확인, 통합 테스트는 deploy/Dockerfile로 만든 `c8ffaa87…`로 쟀다. 두 이미지는 레이어와 환경변수가 같다 |
| musl 비교 이미지 | `webp-imageio-server:musl-baseline` sha256 `7001fdc9…`(ADR-0012 구성: musl 정적 + `scratch`). research/0014가 쓴 이미지와 같다. 요청당 page fault 측정에만 썼고, seccomp는 main의 musl용 프로파일을 걸었다 |
| 다른 날 비교 기준 | Phase 5 JMH와 디코딩 시간([bench/results/2026-10-29-phase5/](../../bench/results/2026-10-29-phase5/), musl), research/0014 60분 부하(2026-10-30, musl), research/0007 메모리 조합(2026-10-29, musl) |
| 호스트 | Intel Core i9-14900, Ubuntu 26.04.1, 커널 7.0.0-42, Docker 29.1.3, runc 1.4.0, OpenJDK 25.0.5 |
| CPU 고정 | JVM 0·2·4·6, 서버 12·14(`--cpus 2`, `--memory 1g`). 서버 안 디코딩 시간은 CPU 12 |
| 커밋 | JMH·부하 `6940866`. 부하 측정의 "(dirty)"는 문서 편집(`docs/roadmap.md`, `docs/research/0013`)이다. 할당자 설정 측정(2026-11-03)은 `7d7b402`에 작업 트리의 서버 변경(유휴 trim)을 더한 상태이고, 그 변경은 이 문서와 같은 브랜치에 커밋했다 |

## 이미지와 격리

| 항목 | musl(전, ADR-0012) | glibc(ADR-0021) |
|---|---|---|
| 이미지 크기 | 2.0 MB(2,046,883 B) | 12.7 MB(12,665,828 B) |
| 실행 베이스 | `scratch` | `gcr.io/distroless/cc-debian13:nonroot`, Debian 패키지 14개 |
| 서버 바이너리 링크 | 정적 | 동적. `NEEDED`는 `libgcc_s.so.1`, `libm.so.6`, `libc.so.6`, `ld-linux-x86-64.so.2` |
| seccomp 허용 목록 | 55개 | 61개(서버 57 + runc 초기화 4) |

- 베이스 패키지(`/licenses/BASE-IMAGE-PACKAGES.tsv`): base-files, ca-certificates, gcc-14-base, libc6 `2.41-12+deb13u4`, libgcc-s1, libgomp1, libssl3t64, libstdc++6, libzstd1, media-types, netbase, tzdata, tzdata-legacy, zlib1g. 서버가 쓰는 것은 libc6와 libgcc-s1이다.
- seccomp 차이:
  - 더한 것(10개): `access`, `clone3`, `epoll_wait`, `madvise`, `newfstatat`, `pread64`, `prlimit64`, `readlinkat`, `rseq`, `set_robust_list`.
  - 뺀 것(4개): `epoll_pwait`, `lstat`, `open`, `stat`(musl에서만 썼다).
  - 수집 절차와 근거: [deploy/README.md](../../deploy/README.md) "seccomp 프로파일". 동적 로더는 이미지 안의 로더로 strace했고, LOG 모드로 통합 테스트, 섞인 부하(스레드 12), 스모크 테스트, SIGTERM 종료를 돌려 목록 밖 호출이 0건임을 봤다.
  - runc의 `readlinkat`(`reopen exec fifo: get safe /proc/thread-self/fd handle`)은 LOG 모드에 나오지 않고 ERRNO 모드 기동에서만 드러났다. 같은 runc로 musl 이미지는 이 호출 없이 뜬다(원인 미확인).
- 최종 프로파일(ERRNO)로 확인한 것:
  - `deploy/scripts/verify-hardening.sh` 23개 항목이 통과했다.
  - client-jvm 통합 테스트 23개, 컨테이너 스모크 테스트(종료 코드 0), 벤치 검증(`bench/run.sh verify`: 테스트 3개, 사이드카 픽셀 일치 30/30)이 통과했다.
  - 목록 밖 호출은 막힌다(같은 프로파일로 `uname`이 실패).

## ADR-0011 재판정(JMH 디코딩 묶음)

`bench/run.sh jmh decode transport features`. Phase 5(musl, 2026-10-29)와 같은 묶음·설정이다. 전체 표는 `summary.md`에 있다.

**판정 대상 18개 조건 중 18개 충족**(Phase 5: 16개). 남았던 사진 alpha M·L이 충족했다.

사진, ops/s(높을수록 빠름):

| 모드 | 크기 | 사이드카 Phase 5 → glibc | JNI Phase 5 → glibc | 대 JNI Phase 5 → glibc | 대 TM(glibc) |
|---|---|---|---|---|---|
| lossy | M | 195 → 217(+11.0%) | 354 → 354 | 0.55 → 0.61 | 5.47 |
| lossy | L | 87.2 → 94.6(+8.5%) | 151 → 152 | 0.58 → 0.62 | 5.99 |
| lossy | XL | 17.4 → 18.3(+5.3%) | 27.6 → 27.2 | 0.63 → 0.68 | 6.71 |
| lossless | M | 62.8 → 68.3(+8.7%) | 92.8 → 92.2 | 0.68 → 0.74 | 4.25 |
| lossless | L | 25.1 → 26.6(+5.7%) | 34.2 → 33.9 | 0.73 → 0.78 | 4.28 |
| lossless | XL | 4.60 → 4.58(−0.4%) | 5.54 → 5.53 | 0.83 → 0.83 | 4.13 |
| alpha | M | 113 → 134(+18.6%) | 249 → 239 | **0.45 → 0.56** | 5.54 |
| alpha | L | 49.2 → 55.0(+11.7%) | 105 → 102 | **0.47 → 0.54** | 5.95 |
| alpha | XL | 10.2 → 10.2(+0.5%) | 18.0 → 18.0 | 0.56 → 0.57 | 6.17 |

- 사이드카는 사진·합성 30개 조건 중 28개에서 빨라졌다(+2.4–20.0%). 느려진 것은 사진 lossless XL(−0.4%) 하나이고 오차 범위다.
- 같은 JVM 안에서 도는 JNI(darkxanter)는 −4.2%–+0.5%였다. 서버를 거치지 않으므로 두 날의 호스트 상태 차이를 보여 주는 기준이다.
  - 가장 크게 움직인 것이 사진 alpha M(−4.2%)이다. Phase 5의 JNI 값으로 나눠도 alpha M 0.54(133.8 / 249.0), L 0.53(55.0 / 104.6)으로 0.50을 넘는다.
- alpha가 없는 입력도 5–14% 빨라졌다. 서버 안 libwebp 디코딩 차이(±2%, 아래)보다 크다. 나머지는 libwebp 밖(gRPC 쪽)에서 나왔다. 아래 "비용 분해"와 "요청당 page fault".

## 비용 분해: 어디서 빨라졌나

사진, UDS, ms. Decode RPC는 TransportBenchmark(이미지 변환 없음)다. 서버 libwebp는 같은 세션 교대 측정(아래, 출력 버퍼 재사용)의 musl → glibc다. 전송·기타는 각 날의 `summary.md` 값(Decode RPC − 그날의 서버 디코딩 시간)이다.

| 모드 | 크기 | Decode RPC Phase 5 → glibc | 서버 libwebp musl → glibc | 전송·기타 Phase 5 → glibc |
|---|---|---|---|---|
| lossy | M | 4.74 → 3.98 | 2.84 → 2.79 | 2.01 → 1.11 |
| lossy | L | 10.01 → 9.06 | 6.43 → 6.53 | 3.71 → 2.47 |
| lossy | XL | 53.1 → 49.5 | 38.1 → 37.8 | 16.9 → 11.5 |
| lossless | M | 14.30 → 12.34 | 11.06 → 10.57 | 3.58 → 1.97 |
| lossless | L | 36.5 → 32.2 | 29.8 → 27.9 | 8.08 → 3.82 |
| lossless | XL | 201 → 188 | 181 → 180 | 27.9 → 6.1 |

- alpha가 없는 입력에서 Decode RPC가 줄어든 몫은 대부분 libwebp 밖이다. 예: lossy M은 Decode RPC가 0.76 ms 줄었는데, 같은 세션의 서버 libwebp 차이는 0.05 ms다. lossless XL은 13 ms 중 1.4 ms다.
- glibc 날의 서버 디코딩 시간은 JMH보다 약 1시간 뒤, 호스트가 더 바쁠 때 쟀다(아래 "서버 안 libwebp"). 값이 높게 나왔다면 glibc 쪽 전송·기타는 그만큼 작게 계산됐다. lossy M에서 약 0.1 ms, XL에서 약 2 ms다(추정).

### 요청당 page fault

`bench/glibc-check/faults.sh`. 이미지마다 새 컨테이너(bench/run.sh와 같은 격리·제한)에 TransportBenchmark Decode를 보내고, 서버 cgroup `memory.stat`의 `pgfault`(서버 프로세스 전체, libwebp 안 포함)를 셌다. 같은 조건을 요청 2B회와 6B회로 돌린 차이를 4B로 나눴다(B: M 100, L 50, XL 20). 그 앞에 한 번 더 돌려 첫 요청과 풀 채우기를 뺐다. 측정일은 2026-11-03이다.

| 입력 | musl | glibc |
|---|---|---|
| M lossy | 417 | 19 |
| M alpha | 915 | 1 |
| M lossless | 1,160 | 0 |
| L lossy | 1,060 | 0 |
| L alpha | 2,358 | 0 |
| L lossless | 2,743 | 0 |
| XL lossy | 6,068 | 0 |
| XL alpha | 13,463 | 5,877 |
| XL lossless | 16,072 | 5,940 |

- glibc는 M·L 모든 모드와 XL lossy에서 1회당 page fault가 거의 없다. 해제한 블록을 다음 요청이 다시 쓴다. musl은 모든 조건에서 요청마다 새 페이지를 만진다.
- glibc의 XL lossless·alpha에 남은 fault는 libwebp 안의 VP8L 디코더 버퍼다.
  - 크기: 디코딩마다 48,275,456 B(46.0 MiB)를 mmap했다가 munmap한다(`strace -e trace=mmap,munmap`, glibc `decode_time`, 출력 버퍼 재사용).
  - 출처: libwebp 1.6.0 `src/dec/vp8l_dec.c` `AllocateInternalBuffers32b`가 4 B × (W×H + W + 16W)를 한 번에 할당한다. 4000×3000이면 48,272,000 B이고, 페이지 단위로 올리면 위 크기와 같다.
  - 이 크기는 glibc mmap 임계값의 동적 상한(32 MiB)을 넘으므로 매번 mmap된다. 출력 버퍼가 아니라서 풀(ADR-0018)로 재사용할 수 없다. musl도 같은 버퍼를 매번 매핑하므로 glibc에서 나빠진 것은 아니다.
  - 1회당 fault 약 5,900번(4 KiB 페이지로 약 23 MiB)은 버퍼 크기의 절반쯤이다. 이유는 확인하지 않았다(미확인). 이 호스트의 THP는 `madvise` 모드이고, glibc 기본값은 huge page를 요청하지 않는다.
- 위 "비용 분해"의 전송·기타 감소와 방향이 같다. 다만 fault 수로 다 설명되지는 않는다.
  - research/0013의 fault당 비용(0.66–0.69 µs, 페이지 채우기만)을 줄어든 fault 수에 곱하면, 줄어든 시간의 30–76%다. 예: lossy M은 398회 × 0.68 µs = 0.27 ms로 0.90 ms 중 30%다. lossless XL은 10,132회 × 0.68 µs = 6.9 ms로 21.8 ms 중 32%다.
  - 나머지는 나누지 않았다(미확인). 후보는 해제(munmap) 비용과 다른 날 비교의 잡음이다. glibc XL alpha의 `strace -c`에서 munmap 평균이 호출당 0.7–1.4 ms였다(46 MiB 해제가 대부분이고, strace 부하가 들어 있다). 줄어든 fault에는 libwebp 안의 몫도 섞여 있다.

## 서버 안 libwebp 디코딩: musl 대 glibc

같은 세션에서 musl 빌드와 glibc 빌드(SSE2, `server/examples/decode_time.rs`)를 번갈아 3바퀴 돌렸다. 바퀴마다 반복 100회이고, 표는 세 바퀴 중앙값의 중앙값(ms)이다. 출력 버퍼는 재사용한다(서버 풀과 같다).

| 입력 | musl | glibc | glibc / musl | research/0013 musl → glibc |
|---|---|---|---|---|
| M lossy | 2.84 | 2.79 | 0.98 | 2.74 → 2.68 |
| M lossless | 11.06 | 10.57 | 0.96 | — |
| M alpha | 5.81 | 5.03 | **0.87** | 5.58 → 4.75 |
| L lossy | 6.43 | 6.53 | 1.02 | 6.32 → 6.23 |
| L lossless | 29.81 | 27.93 | 0.94 | — |
| L alpha | 14.44 | 11.79 | **0.82** | 13.37 → 11.30 |
| XL lossy | 38.07 | 37.75 | 0.99 | 36.15 → 35.95 |
| XL lossless | 181.2 | 179.8 | 0.99 | — |
| XL alpha | 77.39 | 74.22 | 0.96 | 71.53 → 67.18 |

- research/0013과 같은 방향이다. alpha M −13%, L −18%, XL −4%이고, alpha가 없는 입력은 −6%–+2%다.
- 절대값은 research/0013(반복 200회, 조용한 호스트)보다 4–10% 높다. 이 날은 데스크톱 작업이 함께 돌아 p90이 넓었다. 비율은 같은 세션 안에서 구했으므로 이 영향을 덜 받는다.

### 측정 도구 수정(`bench/run.sh`의 서버 디코딩 시간 단계)

- 처음 낸 `summary.md`의 서버 libwebp 값은 서버와 다른 방식이었다. `decode_time` 예제의 기본 동작은 요청마다 출력 버퍼를 새로 할당하는데, 서버는 풀로 재사용한다(ADR-0018).
- Phase 5는 명령에서 `DECODE_TIME_REUSE=1`을 따로 줬다(research/0011 "재현"). 이번 전체 실행은 그 변수 없이 돌았다. 그 값은 `decode-time-*-alloc.tsv`로 남겼다.
- `bench/run.sh`가 이 단계에서 `DECODE_TIME_REUSE=1`을 직접 주게 고쳤다. `decode-time-*.tsv`와 `summary.md`는 고친 도구로 다시 쟀다.
- 새 할당과 재사용의 차이(같은 세션 교대 측정, ms):

  | 입력 | musl 재사용 → 새 할당 | glibc 재사용 → 새 할당 |
  |---|---|---|
  | M lossy | 2.84 → 3.38(+19%) | 2.79 → 2.77(−1%) |
  | M alpha | 5.81 → 6.75(+16%) | 5.03 → 6.30(+25%) |
  | L lossy | 6.43 → 8.26(+29%) | 6.53 → 6.56(0%) |
  | XL lossy | 38.1 → 48.8(+28%) | 37.8 → 47.8(+27%) |

  - glibc도 큰 버퍼(64비트 mmap 임계값 동적 상한 32 MiB 초과, XL 48 MB)는 매번 mmap한다. 출력 버퍼 풀을 유지한 ADR-0021의 판단과 맞는다.
  - glibc alpha M이 새 할당에서 25% 느린 이유는 확인하지 않았다(미확인).

## 메모리: ADR-0016 확인 조합

`server/scripts/measure-memory.sh 1g 2 8192`, `1g 2 4096`. 작업마다 새 컨테이너, 동시 2, 메모리 1 GiB, cgroup `memory.peak`(MiB).

| 작업 | 변 | musl(research/0007, ADR-0016 구현 뒤) | glibc | 결과(두 이미지 같음) |
|---|---|---|---|---|
| lossless 디코딩 | 8192 | 293 | 296(+1%) | 통과 |
| lossy 디코딩 | 8192 | 332 | 337(+2%) | 통과 |
| lossless 인코딩 | 8192 | 5 | 5 | `RESOURCE_EXHAUSTED` |
| lossy 인코딩 | 8192 | 6 | 5 | `RESOURCE_EXHAUSTED` |
| 애니메이션 디코딩 | 8192 | 5 | 6 | `RESOURCE_EXHAUSTED` |
| 애니메이션 인코딩 | 8192 | 5 | 5 | `RESOURCE_EXHAUSTED` |
| lossless 디코딩 | 4096 | 142 | 144(+1%) | 통과 |
| lossy 디코딩 | 4096 | 179 | 176(−2%) | 통과 |
| lossless 인코딩 | 4096 | 520 | 541(+4%) | 통과 |
| lossy 인코딩 | 4096 | 594 | 600(+1%) | 통과 |
| 애니메이션 디코딩 | 4096 | 277 | 299(+8%) | 통과 |
| 애니메이션 인코딩 | 4096 | 5 | 5 | `RESOURCE_EXHAUSTED` |

- OOM은 0건이다. 통과·거부도 musl과 같다.
- 새 컨테이너에서 최고 메모리 차이는 −2%–+8%다. research/0007의 미확인 항목("musl과 glibc 할당기의 차이가 최고 메모리에 주는 영향")의 답이다. 이미 데워진 컨테이너는 아래 "부하 뒤 확인"에서 본다.

## 60분 섞인 부하

`bench/soak.sh soak 60 3 4`. research/0014와 같은 조건이다(JVM 3개 × 스레드 4, 같은 작업 가중치, 플러그인 혼잡 재시도 3회, 서버 기본 제한).

### 처리량과 오류

| 항목 | musl(research/0014) | glibc |
|---|---|---|
| 성공 | 230,492건 | 249,704건 |
| 평균 처리량 | 64.02 ops/s | 69.37 ops/s(+8.4%) |
| 1분 구간 표준편차 | 1.9 | 2.2 |
| 10분 구간 평균 | 62.7–64.9 | 68.0–70.4 |
| busy 거부(시도 대비) | 2.54% | 2.37% |
| 그 밖의 실패 | 0 | 0 |
| 서버 CPU | 1.87코어 | 1.85코어 |

작업별 지연(JVM 1, p50 / p99 ms):

| 작업 | musl | glibc |
|---|---|---|
| M lossy 읽기 | 106.5 / 471.8 | 95.8 / 429.0 |
| M alpha 읽기 | 111.1 / 473.3 | 99.1 / 429.9 |
| M lossless 읽기 | 122.5 / 489.8 | 111.1 / 452.6 |
| L lossy 읽기 | 113.8 / 477.0 | 103.3 / 422.7 |
| M lossy 쓰기 | 147.0 / 515.9 | 135.6 / 464.1 |
| M lossless 쓰기 | 320.6 / 739.4 | 303.8 / 657.7 |
| 애니메이션 모든 프레임 | 288.6 / 793.1 | 253.8 / 678.2 |

- 지연 대부분은 서버 대기다(research/0014). 서버가 빨라진 만큼 대기가 줄었다.
- research/0014에서 본 `ignoring HEADERS frame for stream RST_STREAM sent`는 이번에 0건이다. `SO_KEEPALIVE` 경고는 연결마다 한 줄로 같다.

### 서버 메모리

10초 표본 360개. anon은 `memory.stat`의 anon(힙)이고, file은 두 이미지 모두 0이다.

| 구간(분) | musl current 평균(최저–최고) | glibc current 평균(최저–최고) | glibc anon 평균 |
|---|---|---|---|
| 0–10 | 59.6(44.0–84.0) | 151.2(시작–182.2) | 149.2 |
| 10–20 | 62.8(43.1–102.3) | 168.6(155.9–190.5) | 166.8 |
| 20–30 | 60.5(42.1–103.1) | 175.7(168.6–205.0) | 174.0 |
| 30–40 | 61.2(44.5–102.1) | 178.9(170.2–206.6) | 177.2 |
| 40–50 | 60.6(44.8–91.1) | 176.5(169.3–193.4) | 174.5 |
| 50–60 | 58.9(43.4–87.1) | 178.8(173.1–200.5) | 176.6 |

- glibc는 처음 20분 동안 오르고(약 175 MiB까지), 그 뒤 40분은 10분 평균이 175.7–178.9 MiB 안에 있다. 20분 이후 선형 기울기는 current +4.2 MiB/시간, anon +3.1 MiB/시간이다. 전체 구간 기울기는 +29 MiB/시간인데 처음 20분이 대부분이다.
- 최고치는 214 MiB(cgroup `memory.peak`)다. musl은 114 MiB였다. OOM과 재시작은 없었다.
- 바닥이 다르다. musl은 요청 사이 바닥이 42–45 MiB로 돌아가고, glibc는 20분 이후 168–173 MiB다. 부하 중 상주 메모리가 약 110–130 MiB 많다.
- 해석: glibc 할당자는 해제된 블록을 운영체제에 돌려주지 않고 다음 할당에 쓰려고 남긴다(스레드별 arena). musl은 큰 블록을 매번 munmap한다. 이 차이가 처리량 이득과 상주 메모리 증가의 공통 원인이다. 처리량 쪽은 요청당 page fault로 확인했다(위). 메모리 쪽이 누수가 아님은 부하 뒤 유휴 반환으로 확인했다(아래).
- 이 60분 부하는 할당자 설정 전(glibc 기본값) 이미지로 쟀다. 최종 설정(유휴 trim + arena 2개)의 부하 중 메모리는 아래 "부하 뒤 확인"의 20분 데우기에 있다(평균 108 MiB, 최고 154 MiB).

## 부하 뒤 확인: 유휴 반환과 데운 컨테이너의 최악 조합

`bench/glibc-check/warm-worst.sh`(2026-11-03). 새 컨테이너 하나(bench/soak.sh와 같은 격리·제한)에서 아래 단계를 차례로 돌렸다. 서버 메모리는 1초마다 기록했다. 할당자 설정 세 가지를 같은 절차로 쟀다.

1. 유휴 5초
2. 섞인 부하 20분(research/0014와 같은 MixedLoad, JVM 3 × 스레드 4)
3. 유휴 60초
4. ADR-0016 확인 조합 중 통과하는 다섯 개(동시 2, `server/tests/memory_budget.rs`)
5. 유휴 60초

ADR-0018의 메모리 확인 기준은 "부하 후 유휴 만료 시간이 지나면 컨테이너 메모리 사용량이 기준선으로 돌아온다"이다. musl 이미지는 부하 뒤 45초에 50 MB → 3.6 MB로 돌아왔다(research/0011).

| 항목 | glibc 기본값 | 유휴 trim | 유휴 trim + `MALLOC_ARENA_MAX=2`(최종) |
|---|---|---|---|
| 데우기 처리량 | 68.05 ops/s | 68.95 ops/s | 66.91 ops/s |
| busy 거부(시도 대비) | 2.38% | 2.30% | 2.43% |
| 그 밖의 실패 | 0 | 0 | 0 |
| 데우기 중 current 평균 / 최고(1초 표본) | 151.5 / 194.1 MiB | 155.4 / 195.5 MiB | 108.0 / 154.0 MiB |
| 데우기 마지막 5분 current 평균 | 159.3 MiB | 164.0 MiB | 109.0 MiB |
| 유휴 60초 뒤 current / anon | 79.2 / 78.4 MiB | 71.7 / 70.6 MiB | **2.3 / 1.7 MiB** |
| 최악 조합 OOM | 0 / 5 | 0 / 5 | 0 / 5 |
| 컨테이너 최고치(`memory.peak`, 전 과정) | 705 MiB | 679 MiB | 605 MiB |
| 최악 조합 뒤 유휴 60초 current / anon | 177.7 / 176.5 MiB | 71.9 / 71.0 MiB | **2.4 / 1.7 MiB** |

- 시작 기준선은 셋 다 current 1.9–2.6 MiB, anon 0.4 MiB다.
- glibc 기본값:
  - 풀 만료는 동작했다. 유휴 30–40초 사이에 약 48 MiB가 돌아갔다(127.8 → 79.4 MiB).
  - 나머지 78 MiB는 돌아가지 않았다. 큰 인코딩을 지난 뒤에는 176 MiB가 남았다. ADR-0018 기준을 맞추지 못한다.
- 유휴 trim(서버가 유휴로 접어들면 `malloc_trim(0)`):
  - 최악 조합 뒤 20초 안에 105 MiB를 더 돌려줬다(177.6 → 72.1 MiB). 그래도 약 71 MiB가 남았다.
  - 남은 곳: `idle-probe.sh`(부하 1분 + 유휴 60초)로 보면 anon 37.4 MiB가 이름 없는 매핑 4개(64 MiB 경계 정렬, 각 7.3–10.8 MiB)에 있다. glibc의 스레드별 arena 힙이다(원자료 `2026-11-03-glibc-idle-probe/trim/idle.txt`).
  - 이유: glibc 2.41의 `malloc_trim`은 모든 arena의 빈 블록(bin)을 `madvise(MADV_DONTNEED)`로 돌려준다. 그러나 꼭대기 블록을 줄이는 `systrim`은 주 arena에만 한다(`malloc/malloc.c` `mtrim`, 5205–5255행). 스레드 arena의 꼭대기는 `free()`에서 trim 임계값을 넘을 때만 줄어든다(`malloc/arena.c` `heap_trim`).
  - 출처: https://sourceware.org/git/?p=glibc.git;a=blob;f=malloc/malloc.c;hb=refs/tags/glibc-2.41 , 같은 태그의 `malloc/arena.c`(확인 2026-11-03). Debian 패키지 `2.41-12+deb13u4`가 이 부분을 패치했는지는 확인하지 않았다(미확인).
- 유휴 trim + `MALLOC_ARENA_MAX=2`:
  - 유휴 60초 안에 기준선 근처(2.3 MiB)로 돌아왔다. 최악 조합 뒤에도 20초 안에 2.5 MiB다.
  - 같은 짧은 시험에서 arena 1개는 1.1 MiB, 2개는 1.3 MiB였다. arena 2개에서 남는 양이 이렇게 작은 이유는 확인하지 않았다(미확인).
  - 부하 중 메모리도 낮다(평균 108 대 155 MiB, 최고 605 대 679 MiB).
  - 처리량은 trim만 쓴 실행보다 3.0% 낮다(66.91 대 68.95 ops/s). arena 기본값인 두 실행(기본값, trim)이 서로 1.3% 차이였으므로, arena 제한의 비용은 2–3%로 본다(추정). arena 잠금 경합 때문일 것이다(추정, 재지 않았다).
  - 요청당 page fault는 arena 기본값과 같다(`faults-arena.tsv`, 같은 세션). M·L 모든 모드와 XL lossy는 0–47회, XL lossless는 둘 다 5,893회, XL alpha는 5,877 대 6,934회다.
- 최악 조합 값은 1초 표본으로 비교하지 않는다. 작업이 1–2초에 끝나 표본이 최고점을 놓친다(예: 최종 설정의 lossless 8192 디코딩이 2.3 MiB로 찍혔다). 그래서 `memory.peak`로 비교했다.
  - 새 컨테이너의 최고치(위 "메모리: ADR-0016 확인 조합")에서 가장 큰 값은 lossy 인코딩 4096의 600 MiB다.
  - 데운 컨테이너의 최고치는 기본값 705, trim 679, 최종 605 MiB다. 모두 메모리 예산 768 MiB(요청 640 + 풀 128, ADR-0016)와 1 GiB 제한 안이다.

## 최종 설정의 단건 디코딩(JMH 일부)

`bench/glibc-check/jmh-subset.sh`(2026-11-03)로 쟀다. 대상은 DecodeBenchmark의 사진 alpha·lossy M·L 사이드카이고, 설정은 bench/run.sh와 같다(fork 3, 예열 5 × 1초, 측정 10 × 1초). 위 JMH 전체 묶음은 할당자 설정 전 이미지로 쟀으므로, 최종 설정(유휴 trim + arena 2개)이 단건 디코딩을 늦추는지 따로 봤다. 원자료: [bench/results/2026-11-03-glibc-jmh-subset/](../../bench/results/2026-11-03-glibc-jmh-subset/)`jmh-subset.tsv`.

- 처음 두 번은 최종 이미지를 먼저, 할당자 설정 전 이미지를 나중에 쟀다.
  - 최종 설정이 5–9%, 이어서 1–8% 느리게 나왔다. 두 번 모두 나중에 잰 쪽이 빨랐다.
  - 같은 최종 이미지도 두 번 사이에 최대 9% 달랐다(lossy M 195.8 → 213.3 ops/s).
- 그래서 순서를 A B B A로 바꿔 다시 쟀다(A: 할당자 설정 전, B: 최종). 시간에 따른 흔들림이 양쪽에 같게 실린다.

  | 사진 | A 1회 | B 1회 | B 2회 | A 2회 | B 평균 / A 평균 |
  |---|---|---|---|---|---|
  | alpha M | 129.8 | 134.7 | 133.3 | 138.1 | +0.1% |
  | alpha L | 57.2 | 57.0 | 56.0 | 57.3 | −1.3% |
  | lossy M | 215.0 | 202.4 | 208.1 | 213.8 | −4.3% |
  | lossy L | 91.2 | 89.1 | 92.1 | 91.5 | −0.8% |

- 같은 이미지끼리도 6%까지 흔들렸다(A alpha M 129.8 대 138.1). JMH 오차(99.9%)는 ±5–7%다. 최종 설정의 단건 디코딩 차이(−4.3%–+0.1%)는 이 흔들림 안이다.
- ADR-0011 비율(같은 세션 JNI 값으로 나눔. JNI는 첫 번째 회차에 쟀다):
  - alpha M 134.0 / 239.9 = 0.56, alpha L 56.5 / 101.7 = 0.56으로 0.50을 넘는다.
  - 전체 묶음 재판정(0.56, 0.54)과 같은 수준이다.
- arena 수만 바꾼 비교(`MALLOC_ARENA_MAX=64`, 유휴 trim은 그대로)도 두 번째 회차에 넣었다. arena 2개보다 빠르지 않았다(alpha M 127.7 대 131.9, lossy M 209.2 대 213.3). 단건 디코딩에서는 arena 제한의 비용이 보이지 않는다.

## ADR-0021 확인 방법별 결과

| 확인 방법 | 결과 | 근거 |
|---|---|---|
| 이미지: `NEEDED`, `/licenses`, 사용자 65532, 셸 없음 | 충족 | 위 "이미지와 격리" |
| 격리: verify-hardening 23개, 통합 테스트 전체, 스모크, 벤치 검증 | 충족. 최종 이미지로 verify-hardening 23개와 client-jvm 통합 테스트 23개를 다시 돌렸다(2026-11-03) | 위 "이미지와 격리" |
| 메모리: ADR-0016 확인 조합 OOM 0건 | 충족(새 컨테이너 12개 조합, 데운 컨테이너 5개 조합 × 설정 3개) | 위 "메모리: ADR-0016 확인 조합", "부하 뒤 확인" |
| 메모리: 60분 섞인 부하에서 증가 추세 없음 | 충족(20분 이후 10분 평균 175.7–178.9 MiB, 기울기 +4.2 MiB/시간). 할당자 설정 전 이미지로 쟀다 | 위 "60분 섞인 부하" |
| 메모리(부하 뒤): 유휴 60초 뒤 10 MiB 미만 | 최종 설정에서 충족(2.3 MiB, 최악 조합 뒤 2.4 MiB). 기본값(79 MiB)과 trim만(72 MiB)은 미달 | 위 "부하 뒤 확인" |
| 메모리(부하 뒤): 데운 컨테이너 최악 조합 OOM 0건 | 충족(최고 605 MiB) | 위 "부하 뒤 확인" |
| 메모리(부하 뒤): 처리량 기록 | 66.91 ops/s. trim만 68.95, 기본값 68.05. arena 제한 비용 약 3%를 결정자가 받아들였다(2026-11-03) | 위 "부하 뒤 확인" |
| 성능: ADR-0011 재판정 | 18/18 충족(할당자 설정 전 이미지). 최종 설정의 사진 alpha M·L은 JMH 일부로 0.56, 0.56 | 위 "ADR-0011 재판정", "최종 설정의 단건 디코딩" |

## 한계

- JMH는 조건마다 한 번씩 쟀고, Phase 5와는 다른 날의 비교다. 서버를 거치지 않는 JNI 기준이 −4.2%–+0.5%로 움직였다.
- 서버 안 디코딩 시간은 바쁜 호스트에서 쟀다. musl 대 glibc 비율은 같은 세션 교대 측정으로 구했다.
- 60분 부하는 한 번이다. 며칠 단위 실행(장기 단편화)은 하지 않았다.
- 60분 부하, JMH 전체 묶음, 메모리 조합, 서버 디코딩 시간은 할당자 설정 전 이미지로 쟀다. 최종 설정은 20분 데우기, 요청당 page fault, JMH 일부(사진 alpha·lossy M·L)로 확인했다.
- arena 제한의 처리량 비용(약 3%)은 20분 실행 한 번씩의 차이다. arena 기본값 두 실행끼리도 1.3% 차이가 났다.
- `MALLOC_ARENA_MAX=1`은 1분 시험만 했다. 동시 처리 수를 늘린 구성(arena 수도 함께 늘림)은 재지 않았다.
- `M_TRIM_THRESHOLD`, `M_MMAP_THRESHOLD`는 재지 않았다. 이 값을 직접 정하면 glibc가 mmap 임계값을 동적으로 올리지 않는다(`mallopt(3)`). 그러면 큰 블록을 다시 매번 mmap할 것이다(추정).
- x86_64 한 호스트만 쟀다.

## 재현

```sh
docker build -f deploy/Dockerfile -t webp-imageio-server:dev .
docker build -f deploy/Dockerfile --build-arg CARGO_FEATURES=simd-avx2 -t webp-imageio-server:dev-avx2 .
BENCH_RESULTS=bench/results/<이름> bench/run.sh jmh decode transport features
BENCH_RESULTS=bench/results/<이름> bench/run.sh decode-time   # 출력 버퍼 재사용(DECODE_TIME_REUSE=1)이 기본이다
server/scripts/measure-memory.sh 1g 2 8192; server/scripts/measure-memory.sh 1g 2 4096
BENCH_RESULTS=bench/results/<이름>-soak bench/soak.sh soak 60 3 4

# 아래는 bench/glibc-check/(사진 세트와 ./gradlew :bench:installBench가 먼저 있어야 한다)
bench/glibc-check/rounds.sh bench/results/<이름>/libc-rounds.tsv 3 100

# musl 비교 이미지: ADR-0021 이전 main(9cfd31f)의 Dockerfile과 seccomp 프로파일
git worktree add /tmp/webp-musl 9cfd31f
docker build -f /tmp/webp-musl/deploy/Dockerfile -t webp-imageio-server:musl-baseline /tmp/webp-musl
bench/glibc-check/faults.sh bench/results/<이름>/faults.tsv \
  "musl=webp-imageio-server:musl-baseline=/tmp/webp-musl/deploy/seccomp/webp-imageio-server.json" \
  "glibc=webp-imageio-server:dev=$PWD/deploy/seccomp/webp-imageio-server.json"

bench/glibc-check/warm-worst.sh bench/results/<이름>-warm 20
EXTRA_DOCKER_ARGS="-e MALLOC_ARENA_MAX=1" bench/glibc-check/idle-probe.sh bench/results/<이름>-idle/arena1
```

할당자 설정별 이미지:

- glibc 기본값(trim 없음): ADR-0021 첫 구현 커밋 `2ad7555`의 deploy/Dockerfile로 만든다.
- 유휴 trim, arena 기본값: 지금 deploy/Dockerfile에서 `ENV MALLOC_ARENA_MAX=2` 줄을 지우고 만든다.
- 최종(trim + arena 2개): 지금 deploy/Dockerfile 그대로 만든다.

`warm-worst.sh`와 `faults.sh`는 이미지 이름을 인자로 받는다. `idle-probe.sh`는 `EXTRA_DOCKER_ARGS`로 환경변수를 더할 수 있다.
