#!/usr/bin/env bash
# glibc 서버 이미지 확인(docs/research/0015-glibc-image-verification.md): DecodeBenchmark 일부(사진 alpha·lossy, M·L)를
# 서버 이미지 하나에 대해 bench/run.sh와 같은 JMH 설정(fork 3, 예열 5, 측정 10)으로 잰다. 할당자 설정이 다른 이미지를
# 같은 세션에서 번갈아 재는 데 쓴다. Linux, Docker, taskset 필요. 사진 세트와 bench 실행 묶음이 먼저 있어야 한다.
#
#   bench/glibc-check/jmh-subset.sh <출력 json> <이미지> [impl 목록, 기본 SIDECAR]
#
# 환경변수 EXTRA_DOCKER_ARGS: docker run에 더할 옵션(예: "-e MALLOC_ARENA_MAX=64"). 이미지 ID와 이 값은 <출력 json>.env에
# 남는다. 서버는 bench/run.sh와 같은 격리 옵션·자원 제한(메모리 1 GiB, CPU 2, CPU 12·14 고정)으로 뜬다.
set -euo pipefail

OUT=$(realpath -m "$1")
IMAGE=$2
IMPL=${3:-SIDECAR}
cd "$(dirname "$0")/../.."
ROOT=$PWD
LIB="$ROOT/bench/build/install/bench/lib"
JAVA=$(cat "$ROOT/bench/build/install/bench/java-executable.txt")
SOCK=$(mktemp -d /tmp/wpj.XXXX)
CNAME="wpj-$$"
trap 'docker rm -f "$CNAME" >/dev/null 2>&1 || true; rm -rf "$SOCK"' EXIT

# shellcheck disable=SC2086
docker run -d --rm --name "$CNAME" --user "$(id -u):$(id -g)" --read-only --cap-drop ALL --security-opt no-new-privileges \
  --security-opt "seccomp=$ROOT/deploy/seccomp/webp-sidecar.json" --memory 1g --memory-swap 1g --cpus 2 \
  --cpuset-cpus 12,14 --network none ${EXTRA_DOCKER_ARGS:-} -v "$SOCK:/run/webp-sidecar" "$IMAGE" >/dev/null
for _ in $(seq 100); do [ -S "$SOCK/webp.sock" ] && break; sleep 0.1; done
[ -S "$SOCK/webp.sock" ] || { docker logs "$CNAME" >&2; exit 1; }
echo "image $IMAGE $(docker image inspect --format '{{.Id}}' "$IMAGE") extra=${EXTRA_DOCKER_ARGS:-}" >"$OUT.env"
taskset -c 0,2,4,6 "$JAVA" -Xmx1g -cp "$LIB/*" org.openjdk.jmh.Main '\.DecodeBenchmark\.' -p impl="$IMPL" -p server=sse2-uds \
  -p content=photo -p mode=alpha,lossy -p size=M,L \
  -jvmArgsPrepend "-Dwebp.bench.server.sse2-uds=unix://$SOCK/webp.sock -Dwebp.bench.samples=$ROOT/samples" \
  -rf json -rff "$OUT" -o "$OUT.txt"
