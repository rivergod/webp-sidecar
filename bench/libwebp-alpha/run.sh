#!/usr/bin/env bash
# 사진 alpha 격차 조사(docs/research/0013-alpha-decode-gap.md): libwebp 디코딩 시간을 빌드·버전·출력 방식별로 잰다.
# Linux, Docker, taskset, Rust musl 타깃 필요. 저장소 루트에서 실행한다. 사진 세트(samples/bench/photo)가 먼저 있어야 한다.
#
#   bench/libwebp-alpha/run.sh [반복 수, 기본 200]
#
# 환경변수: BENCH_RESULTS(결과 디렉터리, 기본 bench/results/<UTC 시각>-alpha), ALPHA_CPUS(기본 "12,14": 첫 CPU에 단일
# 스레드를 고정하고, 스레드 1 측정은 두 CPU를 준다. bench/run.sh의 서버 CPU와 같다).
#
# 측정 묶음
#   server-build.tsv  서버 빌드(libwebp-sys 0.14.4 = libwebp 1.6.0): {musl, glibc} × {sse2(기본), avx2(sse4.1 포함)}.
#                     server/examples/decode_time.rs, 출력 버퍼 재사용(서버 Decode와 같은 경로)
#   c-harness.tsv     공식 빌드(CMake Release, 정적, glibc, SIMD 런타임 선택): libwebp {1.3.2, 1.6.0} × 출력 방식 ×
#                     스레드 {0, 1}, 그리고 사이 릴리스 {1.4.0, 1.5.0}의 rgba·yuv(어느 릴리스에서 바뀌었는지). alpha_decode.c
#                     1.6.0-dispatch150(1.6.0 + 1.5.0의 alpha_processing_sse2.c)의 rgba는 원인 확인용이다. 첫 열은
#                     라이브러리 버전 대신 이 이름으로 적는다.
set -euo pipefail

cd "$(dirname "$0")/../.."
ROOT=$PWD
N=${1:-200}
CPUS=${ALPHA_CPUS:-12,14}
CPU=${CPUS%%,*}
RESULTS=${BENCH_RESULTS:-bench/results/$(date -u +%Y%m%dT%H%M%SZ)-alpha}
IMAGE=webp-libwebp-alpha:1.3.2-1.6.0
PHOTO=samples/bench/photo
FILES=(M_1024x768_lossy_q75 M_1024x768_lossy_alpha_q75 L_1920x1080_lossy_q75 L_1920x1080_lossy_alpha_q75
  XL_4000x3000_lossy_q75 XL_4000x3000_lossy_alpha_q75)

log() { printf '[alpha %s] %s\n' "$(date -u +%H:%M:%S)" "$*" >&2; }

for f in "${FILES[@]}"; do
  [ -f "$PHOTO/$f.webp" ] || { echo "missing $PHOTO/$f.webp (bench/run.sh inputs)" >&2; exit 1; }
done
mkdir -p "$RESULTS"
docker build -q -t "$IMAGE" bench/libwebp-alpha >/dev/null
{
  echo "date_utc: $(date -u +%Y-%m-%dT%H:%M:%SZ)"
  echo "git: $(git rev-parse HEAD)$(git diff --quiet HEAD -- . ':!bench/results' || echo ' (dirty)')"
  echo "cpu: $(lscpu | sed -n 's/^Model name: *//p' | head -1), pinned $CPUS"
  echo "image: $(docker image inspect --format '{{.Id}}' "$IMAGE")"
  echo "iterations: $N"
  for f in "${FILES[@]}"; do echo "input: $f.webp $(sha256sum "$PHOTO/$f.webp" | cut -d' ' -f1)"; done
} >"$RESULTS/environment.txt"

# 1) 서버 빌드: 서버와 같은 함수(features + decode_still_rgba_into), 같은 libwebp-sys 빌드.
out="$RESULTS/server-build.tsv"
echo -e "build\tfile\tmedian_ms\tmin_ms\tp90_ms\titerations" >"$out"
paths=()
for f in "${FILES[@]}"; do paths+=("$ROOT/$PHOTO/$f.webp"); done
for target in x86_64-unknown-linux-musl x86_64-unknown-linux-gnu; do
  for simd in sse2 avx2; do
    feat=()
    dir="$ROOT/server/target/alpha-$simd"
    [ "$simd" = avx2 ] && feat=(--features simd-avx2)
    log "server build $target $simd"
    (cd server && cargo build -q --release --locked --target "$target" --example decode_time --target-dir "$dir" \
      ${feat[@]+"${feat[@]}"})
    libc=$([ "$target" = x86_64-unknown-linux-musl ] && echo musl || echo glibc)
    DECODE_TIME_REUSE=1 taskset -c "$CPU" "$dir/$target/release/examples/decode_time" "$N" "${paths[@]}" \
      | tail -n +2 | sed "s|$ROOT/$PHOTO/||" | sed "s|^|$libc-$simd\t|" >>"$out"
  done
done

# 2) 공식 빌드: 버전 × 출력 방식 × 스레드. 컨테이너는 네트워크를 끄고 CPU를 고정한다.
out="$RESULTS/c-harness.tsv"
echo -e "libwebp\tmode\tthreads\toutput\tfile\tmedian_ms\tmin_ms\tp90_ms\titerations" >"$out"
inputs=()
for f in "${FILES[@]}"; do inputs+=("/in/$f.webp"); done
c_run() { # c_run <버전> <모드> <스레드> <into|alloc>
  local cpus=$CPU
  [ "$3" = 1 ] && cpus=$CPUS
  docker run --rm --network none --cpuset-cpus "$cpus" -v "$ROOT/$PHOTO:/in:ro" "$IMAGE" \
    "alpha_decode-$1" "$N" "$2" "$3" "$4" "${inputs[@]}" | tail -n +2 | sed "s/^[^\t]*/$1/" >>"$out"
}
for v in 1.3.2 1.4.0 1.5.0 1.6.0; do
  c_run "$v" rgba 0 into
  case "$v" in 1.4.0 | 1.5.0) log "c-harness $v rgba, yuv"; c_run "$v" yuv 0 alloc; continue ;; esac
  for mode in bgra argb rgbA; do
    log "c-harness $v $mode"
    c_run "$v" "$mode" 0 into
  done
  log "c-harness $v rgba threads=1, rgba alloc, yuv"
  c_run "$v" rgba 1 into
  c_run "$v" rgba 0 alloc
  c_run "$v" yuv 0 alloc
done
log "c-harness 1.6.0-dispatch150 rgba"
c_run 1.6.0-dispatch150 rgba 0 into
log "done: $RESULTS"
