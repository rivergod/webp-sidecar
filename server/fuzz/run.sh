#!/usr/bin/env bash
# 서버 입력 경로 퍼징(Phase 5-1). Docker 안에서 cargo-fuzz(libFuzzer) + ASan으로 Rust와 libwebp C 코드를 함께 계측한다.
#
#   server/fuzz/run.sh [대상] [초] [작업 수] [CPU]
#     대상: decode(기본) | get_features
#     초: 실행 시간(기본 600), 작업 수: libFuzzer -fork 수(기본 8), CPU: docker --cpuset-cpus(기본 16-31, E코어)
#
# 씨앗: samples/generated, samples/malformed, samples/conformance(있으면). 새로 찾은 입력은 server/fuzz/corpus/<대상>,
# 실패 입력은 server/fuzz/artifacts/<대상>에 쌓인다(커밋하지 않음). 빌드만 네트워크를 쓰고 퍼징은 --network none이다.
set -euo pipefail
cd "$(dirname "$0")/../.."
ROOT=$PWD
TARGET=${1:-decode}
SECS=${2:-600}
FORKS=${3:-8}
CPUS=${4:-16-31}
IMAGE=webp-imageio-fuzz:nightly-llvm23
CACHE=webp-imageio-fuzz-cache

docker build -q -t "$IMAGE" server/fuzz >/dev/null
# 캐시 볼륨(cargo 레지스트리, 빌드 산출물)을 현재 사용자 소유로 만든다.
docker run --rm -v "$CACHE:/cache" "$IMAGE" chown "$(id -u):$(id -g)" /cache

# server/rust-toolchain.toml(stable 고정) 대신 이미지에 든 nightly를 이름(날짜)으로 고정해 쓴다(cargo-fuzz의 -Zsanitizer).
# "nightly"로만 주면 rustup이 최신판을 확인하러 네트워크에 나간다.
# libwebp(C)는 cc 크레이트가 CFLAGS로 컴파일한다: ASan + libFuzzer 커버리지(rustc와 같은 LLVM 23).
run=(docker run --rm -u "$(id -u):$(id -g)" --cpuset-cpus "$CPUS"
  -v "$ROOT:/src" -v "$CACHE:/cache" -w /src/server/fuzz
  -e CARGO_HOME=/cache/cargo -e CARGO_TARGET_DIR=/cache/target
  -e RUSTUP_TOOLCHAIN=nightly-2026-10-28
  -e CC=clang-23 -e CXX=clang++-23 -e "CFLAGS=-fsanitize=address -fsanitize=fuzzer-no-link"
  "$IMAGE")

"${run[@]}" cargo fuzz build "$TARGET"
"${run[@]}" sh -c 'rustc -vV | grep -E "^(release|LLVM)"; clang-23 --version | head -1'

mkdir -p "server/fuzz/corpus/$TARGET" "server/fuzz/artifacts/$TARGET"
seeds=(/src/samples/generated /src/samples/malformed)
[ -d samples/conformance ] && seeds+=(/src/samples/conformance)
# 실패를 찾으면 멈춘다(기본). 결과 요약은 마지막 줄들(#… DONE, 실행 수, 커버리지)과 artifacts 디렉터리로 본다.
"${run[@]:0:3}" --network none "${run[@]:3}" \
  cargo fuzz run "$TARGET" "corpus/$TARGET" "${seeds[@]}" -- \
  -fork="$FORKS" -max_total_time="$SECS" -max_len=262144 -rss_limit_mb=2048 -timeout=20 \
  -artifact_prefix="artifacts/$TARGET/" -print_final_stats=1
