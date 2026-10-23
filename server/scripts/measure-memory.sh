#!/bin/sh
# 메모리 예산 측정(ADR-0012). 작업마다 새 컨테이너를 띄워 cgroup v2 memory.peak와 OOM 여부를 출력한다.
# 사용: server/scripts/measure-memory.sh [메모리 제한, 기본 1g] [동시 요청 수, 기본 1] [변 픽셀, 기본 8192]
# 작업 선택: OPS="decode-anim encode-anim" server/scripts/measure-memory.sh ...
# 필요: Docker, cgroup v2, webp-imageio-server:dev 이미지(deploy/Dockerfile).
set -eu
MEM=${1:-1g}
CONCURRENT=${2:-1}
SIDE=${3:-8192}
# AF_UNIX 경로 길이 한도(약 107바이트) 때문에 짧은 임시 경로를 쓴다.
SOCKDIR=$(mktemp -d /tmp/wpm.XXXXXX)
cd "$(dirname "$0")/.."
cargo test --release --test memory_budget --no-run >/dev/null 2>&1
for OP in ${OPS:-decode-lossless decode-lossy encode-lossless encode-lossy decode-anim encode-anim}; do
  docker rm -f webp-mem >/dev/null 2>&1 || true
  docker run -d --name webp-mem --read-only --network none --cap-drop ALL --security-opt no-new-privileges \
    --memory "$MEM" --memory-swap "$MEM" --cpus 2 --user "$(id -u):$(id -g)" \
    -e WEBP_SERVER_MAX_CONCURRENCY="$CONCURRENT" -v "$SOCKDIR:/run/webp-imageio" webp-imageio-server:dev >/dev/null
  sleep 1
  CG=/sys/fs/cgroup/system.slice/docker-$(docker inspect -f '{{.Id}}' webp-mem).scope
  if WEBP_IMAGEIO_TEST_UDS="$SOCKDIR/webp.sock" WEBP_IMAGEIO_TEST_OP="$OP" WEBP_IMAGEIO_TEST_SIDE="$SIDE" \
     WEBP_IMAGEIO_TEST_CONCURRENT="$CONCURRENT" cargo test --release --test memory_budget -- --ignored >/dev/null 2>&1; then
    RESULT=ok
  else
    RESULT=FAILED
  fi
  # OOM으로 서버 프로세스가 죽으면 컨테이너가 내려가 cgroup 디렉터리가 사라진다. 그때는 OOMKilled로 판정한다.
  if [ -f "$CG/memory.peak" ]; then
    PEAK=$(awk '{printf "%.0f", $1/1048576}' "$CG/memory.peak")
  else
    PEAK=n/a
  fi
  OOM=$(docker inspect -f '{{.State.OOMKilled}}' webp-mem)
  echo "op=$OP side=$SIDE concurrent=$CONCURRENT mem_limit=$MEM peak_mib=$PEAK oom_killed=$OOM result=$RESULT"
done
docker rm -f webp-mem >/dev/null 2>&1 || true
rm -rf "$SOCKDIR"
