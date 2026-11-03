#!/usr/bin/env bash
# glibc 서버 이미지 확인(docs/research/0015-glibc-image-verification.md): 서버 libwebp 디코딩 시간을 musl·glibc 빌드
# (SSE2)와 출력 방식(풀과 같은 재사용 reuse, 매번 새로 할당 alloc)별로, 같은 세션에서 번갈아 여러 바퀴 잰다.
# 시간대에 따른 호스트 잡음이 한쪽에만 실리지 않게 한다. Linux, taskset, Rust musl·gnu 타깃 필요. 사진 세트가 먼저 있어야 한다.
#
#   bench/glibc-check/rounds.sh <출력 tsv> [바퀴 수, 기본 3] [반복 수, 기본 100]
#
# 서버 CPU 하나(12)에 고정한다. musl 빌드는 bench/libwebp-alpha/run.sh와 같은 target 디렉터리를 쓴다.
set -euo pipefail

OUT=$(realpath -m "$1")
ROUNDS=${2:-3}
N=${3:-100}
cd "$(dirname "$0")/../.."
ROOT=$PWD
files=()
for size in XS_64x64 S_256x256 M_1024x768 L_1920x1080 XL_4000x3000; do
  for mode in lossy_q75 lossless lossy_alpha_q75; do
    files+=("$ROOT/samples/bench/photo/${size}_${mode}.webp")
  done
done
(cd server && cargo build -q --release --locked --target x86_64-unknown-linux-musl --example decode_time \
  --target-dir "$ROOT/server/target/alpha-sse2")
(cd server && cargo build -q --release --locked --target x86_64-unknown-linux-gnu --example decode_time)
declare -A BIN=(
  [musl]="$ROOT/server/target/alpha-sse2/x86_64-unknown-linux-musl/release/examples/decode_time"
  [glibc]="$ROOT/server/target/x86_64-unknown-linux-gnu/release/examples/decode_time"
)
echo -e "round\tlibc\toutput\tfile\tmedian_ms\tmin_ms\tp90_ms\titerations" >"$OUT"
for r in $(seq 1 "$ROUNDS"); do
  for libc in musl glibc; do
    for output in reuse alloc; do
      reuse=0
      [ "$output" = reuse ] && reuse=1
      echo "[rounds $(date -u +%H:%M:%S)] round $r $libc $output" >&2
      DECODE_TIME_REUSE=$reuse taskset -c 12 "${BIN[$libc]}" "$N" "${files[@]}" \
        | tail -n +2 | sed "s|$ROOT/samples/bench/photo/||" | sed "s|^|$r\t$libc\t$output\t|" >>"$OUT"
    done
  done
done
