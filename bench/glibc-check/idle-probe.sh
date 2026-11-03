#!/usr/bin/env bash
# glibc 서버 이미지 확인(docs/research/0015-glibc-image-verification.md): 짧은 섞인 부하 뒤 유휴 60초가 지난 서버의
# 상주 메모리를 매핑별로 본다(어느 arena 힙에 남았는지). Linux, Docker, taskset 필요. 사진 세트와 bench 실행 묶음
# (./gradlew :bench:installBench)이 먼저 있어야 한다. 서버 프로세스의 /proc/<pid>/smaps를 읽으므로 컨테이너를 현재
# 사용자로 띄운다.
#
#   bench/glibc-check/idle-probe.sh <출력 디렉터리> [이미지, 기본 webp-imageio-server:dev] [부하 초, 기본 60]
#
# 환경변수 EXTRA_DOCKER_ARGS: docker run에 더할 옵션(예: "-e MALLOC_ARENA_MAX=1").
# 출력: idle.txt(부하 끝·유휴 60초 anon, 스레드 수, smaps_rollup, anon 512 KiB 넘는 매핑 목록), smaps.txt(원본),
# idle-load-<n>.txt(MixedLoad 요약).
set -euo pipefail

OUT=$(realpath -m "$1")
IMAGE=${2:-webp-imageio-server:dev}
SECS=${3:-60}
cd "$(dirname "$0")/../.."
ROOT=$PWD
LIB="$ROOT/bench/build/install/bench/lib"
JAVA=$(cat "$ROOT/bench/build/install/bench/java-executable.txt")
mkdir -p "$OUT"
SOCK=$(mktemp -d /tmp/wpp.XXXX)
CNAME="wpp-$$"
trap 'docker rm -f "$CNAME" >/dev/null 2>&1 || true; rm -rf "$SOCK"' EXIT

# bench/soak.sh와 같은 격리·자원 제한.
# shellcheck disable=SC2086
docker run -d --name "$CNAME" --user "$(id -u):$(id -g)" --read-only --network none --cap-drop ALL \
  --security-opt no-new-privileges --security-opt "seccomp=$ROOT/deploy/seccomp/webp-imageio-server.json" \
  --memory 1g --memory-swap 1g --cpus 2 --cpuset-cpus 12,14 --pids-limit 128 ${EXTRA_DOCKER_ARGS:-} \
  -v "$SOCK:/run/webp-imageio" "$IMAGE" >/dev/null
for _ in $(seq 100); do [ -S "$SOCK/webp.sock" ] && break; sleep 0.1; done
pid=$(docker inspect "$CNAME" --format '{{.State.Pid}}')
CG=/sys/fs/cgroup$(awk -F: '$1 == "0" { print $3 }' "/proc/$pid/cgroup")
anon() { awk '$1 == "anon" { printf "%.1f", $2 / 1048576 }' "$CG/memory.stat"; }
echo "image: $IMAGE $(docker image inspect --format '{{.Id}}' "$IMAGE") extra_docker_args: ${EXTRA_DOCKER_ARGS:-}" >"$OUT/idle.txt"

pids=()
for i in 1 2 3; do
  taskset -c 0,2,4,6 "$JAVA" -Xms1g -Xmx2g -Dwebp.imageio.endpoint="unix://$SOCK/webp.sock" -Dwebp.imageio.busy-retries=3 \
    -Dwebp.bench.samples="$ROOT/samples" -cp "$LIB/*" net.rivergod.webp.imageio.bench.MixedLoad mixed 4 "$SECS" 60 \
    >"$OUT/idle-load-$i.txt" 2>/dev/null &
  pids+=($!)
done
for p in "${pids[@]}"; do wait "$p"; done
echo "load_end anon_mib=$(anon)" | tee -a "$OUT/idle.txt"

# 풀 유휴 만료(30초) + 정리 주기(15초)보다 길게 쉰다(ADR-0018, ADR-0021).
sleep 60
echo "idle60 anon_mib=$(anon) threads=$(find "/proc/$pid/task" -mindepth 1 -maxdepth 1 | wc -l)" | tee -a "$OUT/idle.txt"
cp "/proc/$pid/smaps" "$OUT/smaps.txt"
grep -E '^(Rss|Anonymous)' "/proc/$pid/smaps_rollup" | tee -a "$OUT/idle.txt"
# anon이 512 KiB를 넘는 매핑: 주소 범위, 매핑 크기, anon(MiB). glibc 스레드 arena 힙은 64 MiB 경계에 정렬된 이름 없는 매핑이다.
awk '/^[0-9a-f]+-[0-9a-f]+ / { range = $1; name = (NF >= 6 ? $6 : "[anon]") }
     $1 == "Size:" { size = $2 / 1024 }
     $1 == "Anonymous:" && $2 > 512 { printf "map anon_mib=%.1f size_mib=%.1f %s %s\n", $2 / 1024, size, range, name }' \
  "$OUT/smaps.txt" | sort -t '=' -k2 -rn | tee -a "$OUT/idle.txt"
