# 0021. 서버 이미지를 glibc 동적 바이너리 + distroless/cc로 바꾼다

- 상태: proposed
- 날짜: 2026-11-02
- 결정자:
- 관련 문서: [ADR-0012](0012-server-runtime-config.md)(서버 실행 구성. 이 ADR은 그중 "이미지 / libc" 항목만 대체한다), [ADR-0009](0009-license.md)(라이선스), [ADR-0018](0018-decode-output-buffer-pool.md)(출력 버퍼 풀), [ADR-0016](0016-memory-budget-admission.md)(메모리 예산), [ADR-0011](0011-hypothesis-criteria.md)(판정 기준), [research/0013](../research/0013-alpha-decode-gap.md)(alpha 격차 원인), [research/0006](../research/0006-license.md) "glibc 동적 이미지의 배포 의무", [research/0009](../research/0009-benchmark-results.md) "할당자"

## 맥락

- 서버 이미지는 musl 정적 바이너리 + `scratch`다(ADR-0012). ADR-0012는 그때 "musl 기본 메모리 할당기는 멀티스레드에서 느릴 수 있다(추정)"를 위험으로 적었다.
- Phase 4에서 같은 코드를 glibc로 빌드하면 사진 lossy·alpha M/L 디코딩이 9–26% 빨랐다(research/0009). Phase 5-2는 그 원인인 출력 버퍼의 반복 mmap을 출력 버퍼 풀로 풀었고(ADR-0018), glibc 이미지는 미뤘다.
- research/0013에서 남은 격차를 찾았다. alpha 디코딩은 libwebp 안에서 요청마다 큰 임시 버퍼를 할당한다. 이것은 풀 밖이다. musl은 이 버퍼를 매번 mmap/munmap해서 1회당 page fault가 M 1,219번, L 3,129번 생긴다(glibc는 0번). 그 결과 서버 안 alpha 디코딩이 musl에서 M 5.58 ms, glibc에서 4.75 ms다(L 13.37 대 11.30 ms).
- ADR-0011 판정에서 남은 미달은 사진 alpha M(0.45)·L(0.47) 두 조건이다. research/0013 추정으로는 할당자 몫(M 0.84 ms)만 줄여도 비율이 오른다. 다른 원인(libwebp 1.6.0 `DispatchAlpha_SSE2`)은 패치하지 않기로 했다(결정자, 2026-11-02, research/0013).
- 결정자 지시(2026-11-02): glibc 기반 이미지 ADR을 쓰고 관련 작업을 모두 포함해 확정한 뒤 코드에 반영한다.

## 결정 동인

1. 서버 안 libwebp 성능, 특히 사진 alpha(ADR-0011).
2. 격리 수준을 유지한다: non-root, read-only 루트, 셸 없음, 네트워크 없음, seccomp 허용 목록(ADR-0012, deploy/README.md).
3. 라이선스 의무를 관리할 수 있어야 한다. glibc는 정적 링크하지 않는다(ADR-0009).
4. 이미지와 공격 표면이 작을수록 좋다.
5. 베이스 이미지 갱신을 반복할 수 있는 절차가 있어야 한다.

## 검토한 선택지

1. **glibc 동적 + `gcr.io/distroless/cc-debian13:nonroot`**
2. glibc 동적 + `gcr.io/distroless/base-nossl-debian13:nonroot` + `libgcc_s.so.1`을 빌드 이미지에서 복사
3. musl 정적 + 다른 할당자(mimalloc, jemalloc, Scudo standalone)로 C `malloc`까지 대체 + `scratch`(research/0013 선택지 F)
4. LLVM libc(full build) 정적 + `scratch`
5. glibc 정적 링크 + `scratch` 또는 `distroless/static`
6. 그대로 둔다(musl 정적 + `scratch`)

## 결정

**선택지 1을 추천한다.**

| 항목 | 결정 |
|---|---|
| 빌드 | `rust:1.99.1-slim-trixie`(index digest 고정)에서 `x86_64-unknown-linux-gnu` 타깃으로 빌드한다. glibc는 동적 링크한다(정적 링크 금지는 그대로, ADR-0009) |
| 실행 이미지 | `gcr.io/distroless/cc-debian13:nonroot`(index digest 고정). 셸과 패키지 관리자가 없고 기본 사용자는 65532다. 이미지 사용자(`USER 65532:65532`), 소켓 디렉터리, 진입점은 지금과 같다 |
| glibc 버전 맞춤 | 빌드 이미지와 실행 이미지는 같은 Debian 릴리스의 같은 libc6를 쓴다(2026-11-02: 둘 다 `2.41-12+deb13u4`). 빌드 쪽 glibc가 더 새로우면 실행 쪽에 없는 심볼 버전을 요구할 수 있으므로 둘을 함께 올린다 |
| 할당자 설정 | 두 가지를 바꾸고 나머지(mmap·trim 임계값 등)는 glibc 기본값을 쓴다. 기본값만으로는 부하 뒤 유휴 메모리가 78–176 MiB로 남아 ADR-0018의 유휴 반환이 성립하지 않았다(research/0015). (1) 유휴 trim: 서버가 유휴로 접어들면 `malloc_trim(0)`을 불러 arena의 빈 페이지를 운영체제에 돌려준다. 정리 주기(풀 유휴 만료의 절반, 기본 15초) 동안 새 요청이 없고 처리·대기 중인 요청이 없을 때 부른다. 그중 마지막 trim 뒤에 요청이 있었거나 그 주기에 풀이 버퍼를 놓았을 때만 부른다. 부하 중에는 부르지 않으므로 해제한 블록을 다음 요청이 다시 쓰는 이득(요청당 page fault가 거의 없다)은 그대로다. (2) arena 수: 이미지에 `MALLOC_ARENA_MAX=2`를 둔다(기본 동시 처리 수와 같다). `malloc_trim`은 스레드별 arena의 꼭대기 블록은 줄이지 않아서, trim만으로는 유휴 뒤 37–72 MiB가 남았다. arena 2개에서는 2.4 MiB로 돌아왔다. 대가로 섞인 부하 처리량이 약 3% 낮았다(결정자 수용, 2026-11-03). 운영자가 동시 처리 수를 늘리면 이 값도 함께 올린다(deploy/README.md) |
| 라이선스 고지 | `/licenses`에 `BASE-IMAGE-PACKAGES.tsv`(베이스 패키지 이름·버전·소스 패키지·소스 버전)를 더한다. 이미지 빌드가 실행 이미지의 dpkg 상태에서 만든다. 라이선스 전문은 베이스 이미지의 `/usr/share/doc/*/copyright`와 `/usr/share/common-licenses/`에 있다. 대응 소스 제공 방식은 로드맵 "첫 배포 전 결정"에 더한다(research/0006) |
| seccomp | glibc 바이너리 기준으로 허용 목록을 다시 수집한다(deploy/README.md 절차: strace → LOG 모드 → ERRNO 모드 최종 확인). musl에서만 쓰던 호출은 뺀다 |
| 출력 버퍼 풀 | 유지한다(ADR-0018). glibc도 mmap 임계값의 동적 상한(64비트 32 MiB, `mallopt(3)`의 `M_MMAP_THRESHOLD`)을 넘는 블록은 매번 mmap한다. libwebp가 출력을 할당하면 XL lossy가 36.0 → 45.1 ms로 느려진다(research/0013, glibc) |
| 측정 도구 | `bench/run.sh`의 서버 디코딩 시간 단계를 glibc 타깃으로 바꾸고, 출력 버퍼를 서버처럼 재사용해 잰다(`DECODE_TIME_REUSE=1`, 컨테이너와 같은 할당자와 같은 출력 경로). `bench/libwebp-alpha/`는 musl·glibc 비교를 그대로 둔다. 이 ADR의 확인에 쓴 비교 도구는 `bench/glibc-check/`에 둔다 |
| 베이스 이미지 갱신 | 두 digest를 함께 올린다. 올릴 때 (1) `BASE-IMAGE-PACKAGES.tsv` 차이, (2) libc6 버전 일치, (3) 격리 확인과 seccomp 최종 프로파일로 통합 테스트 전체를 확인한다. distroless는 cosign으로 서명돼 있어 확인을 권한다 |

### 결과

- 긍정: 서버 안 alpha 디코딩에서 musl 할당자 몫이 없어진다(research/0013: M 5.58 → 4.75 ms, L 13.37 → 11.30 ms, XL 71.5 → 67.2 ms). alpha가 없는 입력은 0.5–2% 빨라진다.
- 긍정: ADR-0012의 "musl 할당기" 위험 항목이 해소된다.
- 긍정(확인, research/0015): ADR-0011 판정이 18개 조건 모두 충족이 됐다(사진 alpha M 0.45 → 0.56, L 0.47 → 0.54). 최종 설정으로 다시 잰 사진 alpha M·L도 0.56, 0.56이다. Decode RPC 1회당 page fault가 거의 없어져(musl 417–16,072회 → glibc 0–19회, XL lossless·alpha 제외) alpha가 없는 입력도 빨라졌다. 60분 섞인 부하 처리량은 +8.4%였다(할당자 설정 전).
- 부정(확인, research/0015): glibc 기본값만으로는 부하 뒤 유휴 메모리가 돌아오지 않는다. 그래서 할당자 설정 두 가지(유휴 `malloc_trim`, `MALLOC_ARENA_MAX=2`)를 더했다. 서버 코드에 glibc 전용 호출 하나가 생기고, 운영자는 동시 처리 수를 늘릴 때 arena 수도 함께 올려야 한다.
- 부정(확인, research/0015): arena 제한으로 섞인 부하 처리량이 약 3% 낮다(68.95 → 66.91 ops/s, 20분 한 번씩, 추정 범위 2–3%). 단건 디코딩에서는 차이가 측정 흔들림 안이었다.
- 부정(확인, research/0015): 부하 중 상주 메모리가 musl보다 크다. 최종 설정의 20분 섞인 부하 평균은 108 MiB, 1초 표본 최고는 154 MiB였다. musl의 60분 부하는 10분 평균 59–63 MiB, 최고 114 MiB였다. 유휴로 돌아가면 2 MiB대로 내려간다.
- 부정: 이미지가 커진다(2.0 MB → 12.7 MB). 서버가 쓰지 않는 라이브러리(libssl3t64, libstdc++6, libgomp1, zlib1g, libzstd1)도 들어간다. 취약점 스캐너가 보고하는 대상도 그만큼 는다.
- 부정: LGPL·GPL 패키지 바이너리(glibc, libgcc 등)를 함께 배포한다. 외부에 배포할 때 대응 소스 제공 의무가 생긴다(research/0006). 방식은 첫 배포 전에 정한다.
- 부정: 동적 로더가 실행 초기에 라이브러리를 열고 매핑하므로 seccomp 허용 목록이 달라진다.
- 부정: 베이스 이미지(Debian 보안 갱신)를 따라 올려야 한다. 지금은 Rust 툴체인 이미지 하나만 따라갔다.

### 확인 방법

- 이미지: 서버 바이너리의 `NEEDED`가 `libgcc_s.so.1`, `libm.so.6`, `libc.so.6`, `ld-linux-x86-64.so.2`뿐이다. `/licenses`에 고지 파일이 있다. 사용자 65532, 셸 없음.
- 격리: `deploy/scripts/verify-hardening.sh` 23개 항목, 새 seccomp 프로파일(ERRNO)로 client-jvm 통합 테스트 전체, 컨테이너 스모크 테스트, 벤치 검증(`bench/run.sh verify`)이 통과한다.
- 메모리: ADR-0016 확인 조합(최악 입력, 메모리 1 GiB, 동시 2)에서 OOM이 0건이다. 60분 섞인 부하(`bench/soak.sh soak 60`)에서 서버 메모리의 증가 추세가 없다.
- 메모리(부하 뒤): 섞인 부하로 데운 같은 컨테이너(`bench/glibc-check/warm-worst.sh`)에서 다음을 본다.
  - 유휴 60초(풀 유휴 만료 + 정리 주기) 뒤 컨테이너 메모리(cgroup `memory.current`)가 기준선 근처(10 MiB 미만)로 돌아온다(ADR-0018의 확인 기준).
  - 이어서 보낸 ADR-0016 확인 조합에서 OOM이 0건이다.
  - 데우는 동안의 처리량을 할당자 설정 전후로 비교해 기록한다. arena 제한의 처리량 비용(약 3%)은 결정자가 받아들였다(2026-11-03). 처음에는 "trim을 넣기 전과 같다"를 기준으로 적었으나, arena 제한을 더하면서 이렇게 고쳤다.
- 성능: 컨테이너와 같은 빌드의 서버 디코딩 시간(`bench/run.sh`의 디코딩 시간 단계)과 JMH 디코딩 묶음으로 ADR-0011을 다시 판정한다.

## 선택지별 장단점

### 1. glibc 동적 + distroless/cc-debian13 (추천)

- 장점: research/0013에서 측정한 빌드와 같은 할당자다. distroless가 Rust 예제에 쓰는 구성이다(research/0006 [S10]). libgcc_s가 dpkg 메타데이터와 copyright 파일을 갖춘 패키지로 들어 있어 고지와 스캐너가 그대로 다룬다.
- 단점: 쓰지 않는 패키지가 들어 있다. 이미지가 12.7 MB다.

### 2. glibc 동적 + distroless/base-nossl + libgcc_s 복사

- 장점: 쓰지 않는 패키지가 줄어든다(libc6와 데이터 패키지만).
- 단점: 복사한 `libgcc_s.so.1`은 dpkg 메타데이터가 없어 스캐너가 보지 못한다. 고지와 버전 추적을 손으로 해야 한다. 빌드 이미지의 gcc 버전과 실행 이미지가 따로 움직인다.

### 3. musl 정적 + 다른 할당자 + scratch

- 장점: `scratch` 이미지와 작은 크기를 유지한다. mimalloc(MIT), jemalloc(BSD-2-Clause), Scudo(Apache-2.0 WITH LLVM-exception)는 허용형이라 LGPL 의무가 생기지 않는다.
- 단점: 정적 musl 바이너리에서 libwebp(C)의 `malloc`까지 바뀌는지 확인하지 않았다(미확인, research/0003, ADR-0018). 할당자마다 성능·메모리 특성을 새로 재야 한다. 결정자가 glibc 안을 택했다(2026-11-02).

### 4. LLVM libc full build

- 장점: 라이선스가 Apache-2.0 WITH LLVM-exception이다.
- 단점: 공식 문서가 일반 애플리케이션용으로 권하지 않는다("The LLVM libc is missing many critical functions needed to build non-trivial applications", https://libc.llvm.org/full_host_build.html, 확인 2026-11-02). LLVM libc를 쓰는 Rust Linux 타깃이 없다(rustc 1.99.1 `--print target-list`, 확인 2026-11-02). 표준 라이브러리 이식이 필요해 프로토타입 범위를 넘는다.

### 5. glibc 정적 링크

- 단점: LGPL의 재링크 허용 의무가 생긴다(research/0006). ADR-0009가 금지했다. glibc 정적 링크는 NSS 등에서 dlopen 제약도 있다(research/0003).

### 6. 그대로 둔다

- 장점: 바꿀 것이 없다. 이미지가 가장 작다.
- 단점: alpha 디코딩의 할당자 몫(M 0.84 ms)이 남는다.

## 참고

- 측정 근거: [research/0013](../research/0013-alpha-decode-gap.md) 표 1(서버 빌드), 표 4(할당 계측)
- 확인 결과: [research/0015](../research/0015-glibc-image-verification.md)(JMH 재판정, 메모리, 60분 부하, 유휴 반환, 할당자 설정 비교)
- 라이선스 근거: [research/0006](../research/0006-license.md) "glibc 동적 이미지의 배포 의무"
- seccomp 재수집 절차: [deploy/README.md](../../deploy/README.md) "seccomp 프로파일"
