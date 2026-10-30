#!/usr/bin/env bash
# 퍼징 코퍼스로 커버리지 보고서를 만든다: Rust(서버 webp.rs)와 libwebp C 코드를 함께 계측한다(docs/research/0010).
# Docker 안 nightly + clang/llvm 23(run.sh와 같은 이미지). 퍼저를 먼저 돌려 server/fuzz/corpus/<대상>이 있어야 한다.
#
#   server/fuzz/coverage.sh [대상...]    기본: decode get_features encode encode_animation
#
# 결과: server/fuzz/coverage/(커밋하지 않음). summary.md(libwebp 디렉터리·서버 파일별 줄·함수 커버리지),
# export.json(llvm-cov export 요약), report.txt(파일별 전체 표). 실행은 --network none이다.
set -euo pipefail
cd "$(dirname "$0")/../.."
ROOT=$PWD
IMAGE=webp-imageio-fuzz:nightly-llvm23
CACHE=webp-imageio-fuzz-cache
OUT=server/fuzz/coverage
TARGETS=("$@")
[ ${#TARGETS[@]} -eq 0 ] && TARGETS=(decode get_features encode encode_animation)

docker build -q -t "$IMAGE" server/fuzz >/dev/null
docker run --rm -v "$CACHE:/cache" "$IMAGE" chown "$(id -u):$(id -g)" /cache
mkdir -p "$OUT"
rm -f "$OUT"/*.profraw "$OUT"/*.profdata

# 커버리지 빌드는 ASan 없이(-s none) 따로 둔 대상 디렉터리에 만든다. libwebp(C)도 clang 커버리지로 계측한다.
BIN=/cache/target-cov/x86_64-unknown-linux-gnu/release
run=(docker run --rm -u "$(id -u):$(id -g)"
  -v "$ROOT:/src" -v "$CACHE:/cache" -w /src/server/fuzz
  -e CARGO_HOME=/cache/cargo -e CARGO_TARGET_DIR=/cache/target-cov
  -e RUSTUP_TOOLCHAIN=nightly-2026-10-28
  -e CC=clang-23 -e CXX=clang++-23
  -e "CFLAGS=-fprofile-instr-generate -fcoverage-mapping -fsanitize=fuzzer-no-link"
  -e "RUSTFLAGS=-C instrument-coverage"
  "$IMAGE")
for t in "${TARGETS[@]}"; do
  "${run[@]}" cargo fuzz build -s none "$t"
done

# 코퍼스(와 디코딩 대상의 씨앗)를 한 번씩 실행한다(-runs=0: 불러오며 실행하고 끝낸다).
objects=()
for t in "${TARGETS[@]}"; do
  inputs="corpus/$t"
  case "$t" in
    decode | get_features)
      inputs="$inputs /src/samples/generated /src/samples/malformed"
      [ -d samples/conformance ] && inputs="$inputs /src/samples/conformance" ;;
  esac
  "${run[@]:0:3}" --network none "${run[@]:3}" sh -c \
    "LLVM_PROFILE_FILE=/src/$OUT/$t-%p.profraw $BIN/$t -runs=0 -rss_limit_mb=2048 $inputs 2>&1 | tail -2"
  objects+=(-object "$BIN/$t")
done

"${run[@]:0:3}" --network none "${run[@]:3}" sh -c "
  llvm-profdata-23 merge -sparse /src/$OUT/*.profraw -o /src/$OUT/all.profdata &&
  llvm-cov-23 export -summary-only -instr-profile=/src/$OUT/all.profdata ${objects[*]} > /src/$OUT/export.json &&
  llvm-cov-23 report -instr-profile=/src/$OUT/all.profdata ${objects[*]} > /src/$OUT/report.txt"
python3 -I server/fuzz/coverage_summary.py "$OUT/export.json" "${TARGETS[*]}" >"$OUT/summary.md"
cat "$OUT/summary.md"
