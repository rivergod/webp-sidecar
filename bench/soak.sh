#!/usr/bin/env bash
# 측정 범위 보강(docs/research/0014-load-soak.md): 첫 호출 지연, 여러 연결, 장시간 섞인 부하와 서버 메모리 추이.
# Linux, Docker, taskset 필요. 저장소 루트에서 실행한다. 사진 세트(samples/bench/photo)가 먼저 있어야 한다.
#
#   bench/soak.sh first-call [횟수, 기본 10]     새 JVM마다 첫 ImageIO.read, 두 번째, 열 번째 읽기 시간
#   bench/soak.sh alloc [반복 수, 기본 200]       작업별 1회당 JVM 할당량(한 스레드)과 GC 부담의 근거
#   bench/soak.sh compare [초, 기본 180]          같은 총 스레드 12개: JVM 1개 × 12 대 JVM 3개 × 4(연결 1개 대 3개)
#   bench/soak.sh soak [분, 기본 60] [JVM 수, 기본 3] [JVM당 스레드, 기본 4]
#                                                 섞인 부하(MixedLoad)를 오래 돌리며 서버 메모리를 10초마다 기록한다
#
# 서버는 측정마다 새 컨테이너다: ADR-0012 기본 제한(메모리 1 GiB, CPU 2, 동시 처리 2), deploy/README.md 격리 옵션,
# seccomp, CPU 12,14 고정. JVM은 CPU 0,2,4,6을 나눠 쓴다. 플러그인 혼잡 재시도를 켠다(busy-retries 3, ADR-0020).
# 결과: BENCH_RESULTS(기본 bench/results/<UTC 시각>-soak).
set -euo pipefail

cd "$(dirname "$0")/.."
ROOT=$PWD
LIB="$ROOT/bench/build/install/bench/lib"
IMAGE=webp-imageio-server:dev
RESULTS=${BENCH_RESULTS:-bench/results/$(date -u +%Y%m%dT%H%M%SZ)-soak}

log() { printf '[soak %s] %s\n' "$(date -u +%H:%M:%S)" "$*" >&2; }

CNAME=""
SOCK=""
SAMPLER=""
cleanup() {
  [ -n "$SAMPLER" ] && kill "$SAMPLER" 2>/dev/null || true
  [ -n "$CNAME" ] && docker rm -f "$CNAME" >/dev/null 2>&1 || true
  [ -n "$SOCK" ] && rm -rf "$SOCK" || true
}
trap cleanup EXIT

server_up() {
  SOCK=$(mktemp -d /tmp/wps.XXXX)
  CNAME="wps-$$-$RANDOM"
  docker run -d --name "$CNAME" --user "$(id -u):$(id -g)" \
    --read-only --network none --cap-drop ALL --security-opt no-new-privileges \
    --security-opt "seccomp=$ROOT/deploy/seccomp/webp-imageio-server.json" \
    --memory 1g --memory-swap 1g --cpus 2 --cpuset-cpus 12,14 --pids-limit 128 \
    -v "$SOCK:/run/webp-imageio" "$IMAGE" >/dev/null
  for _ in $(seq 100); do [ -S "$SOCK/webp.sock" ] && break; sleep 0.1; done
  [ -S "$SOCK/webp.sock" ] || { docker logs "$CNAME" >&2; exit 1; }
  local pid
  pid=$(docker inspect "$CNAME" --format '{{.State.Pid}}')
  CGROUP=/sys/fs/cgroup$(awk -F: '$1 == "0" { print $3 }' "/proc/$pid/cgroup")
}

# 서버 메모리 최고치(MiB), OOM 여부, 재시작 수를 출력하고 컨테이너를 지운다.
server_down() {
  local peak oom
  peak=$(awk '{ printf "%.0f", $1 / 1048576 }' "$CGROUP/memory.peak" 2>/dev/null || echo "?")
  oom=$(docker inspect "$CNAME" --format '{{.State.OOMKilled}} running={{.State.Running}}')
  docker rm -f "$CNAME" >/dev/null
  rm -rf "$SOCK"
  CNAME=""
  SOCK=""
  printf 'server_peak_mib=%s oom_killed=%s' "$peak" "$oom"
}

# 서버 cgroup 메모리를 10초마다 기록한다: 경과 초, current, anon(힙), file(페이지 캐시) MiB, 누적 CPU 초.
start_sampler() {
  local out=$1 t0
  t0=$(date +%s)
  echo -e "t_s\tcurrent_mib\tanon_mib\tfile_mib\tcpu_s" >"$out"
  (
    while [ -f "$CGROUP/memory.current" ]; do
      awk -v t="$(($(date +%s) - t0))" -v cur="$(cat "$CGROUP/memory.current")" \
        -v cpu="$(awk '$1 == "usage_usec" { print $2 }' "$CGROUP/cpu.stat")" \
        '$1 == "anon" { a = $2 } $1 == "file" { f = $2 }
         END { printf "%d\t%.1f\t%.1f\t%.1f\t%.1f\n", t, cur / 1048576, a / 1048576, f / 1048576, cpu / 1e6 }' \
        "$CGROUP/memory.stat" >>"$out" || true
      sleep 10
    done
  ) &
  SAMPLER=$!
}

stop_sampler() {
  [ -n "$SAMPLER" ] && kill "$SAMPLER" 2>/dev/null || true
  SAMPLER=""
}

java_run() { # java_run <MixedLoad 인자...>
  taskset -c 0,2,4,6 "$JAVA" -Xms1g -Xmx2g -Dwebp.imageio.endpoint="unix://$SOCK/webp.sock" \
    -Dwebp.imageio.busy-retries=3 -Dwebp.bench.samples="$ROOT/samples" \
    -cp "$LIB/*" net.rivergod.webp.imageio.bench.MixedLoad "$@"
}

# JVM <n>개를 동시에 띄워 mixed를 돌리고 각 출력을 <prefix>-<i>.txt에 남긴다.
run_jvms() { # run_jvms <n> <스레드> <초> <간격> <prefix>
  local pids=()
  for i in $(seq 1 "$1"); do
    java_run mixed "$2" "$3" "$4" >"$5-$i.txt" 2>"$5-$i.err" &
    pids+=($!)
  done
  for p in "${pids[@]}"; do wait "$p"; done
}

record_env() {
  {
    echo "date_utc: $(date -u +%Y-%m-%dT%H:%M:%SZ)"
    echo "git: $(git rev-parse HEAD)$(git diff --quiet HEAD -- . ':!bench/results' || echo ' (dirty)')"
    echo "image: $(docker image inspect --format '{{.Id}}' "$IMAGE")"
    echo "java: $("$JAVA" -version 2>&1 | head -1)"
    echo "cpu: $(lscpu | sed -n 's/^Model name: *//p' | head -1)"
  } >"$RESULTS/environment.txt"
}

cmd=${1:-}
[ "$#" -gt 0 ] && shift
./gradlew -q --console=plain :bench:installBench
JAVA=$(cat "$ROOT/bench/build/install/bench/java-executable.txt")
mkdir -p "$RESULTS"
record_env
case "$cmd" in
  first-call)
    runs=${1:-10}
    server_up
    for i in $(seq 1 "$runs"); do
      log "first-call $i/$runs"
      echo "run=$i $(java_run first-call)" | tee -a "$RESULTS/first-call.txt"
    done
    echo "$(server_down)" >>"$RESULTS/first-call.txt" ;;
  alloc)
    reps=${1:-200}
    server_up
    log "alloc reps=$reps"
    java_run alloc "$reps" | tee "$RESULTS/alloc.txt"
    echo "$(server_down)" >>"$RESULTS/alloc.txt" ;;
  compare)
    secs=${1:-180}
    for layout in "1 12" "3 4"; do
      read -r jvms threads <<<"$layout"
      log "compare jvms=$jvms threads=$threads ${secs}s"
      server_up
      run_jvms "$jvms" "$threads" "$secs" 30 "$RESULTS/compare-${jvms}x${threads}"
      echo "jvms=$jvms threads=$threads $(server_down)" | tee -a "$RESULTS/compare.txt"
    done ;;
  soak)
    minutes=${1:-60}
    jvms=${2:-3}
    threads=${3:-4}
    log "soak ${minutes}min jvms=$jvms threads=$threads"
    server_up
    start_sampler "$RESULTS/server-memory.tsv"
    run_jvms "$jvms" "$threads" $((minutes * 60)) 60 "$RESULTS/soak"
    stop_sampler
    echo "minutes=$minutes jvms=$jvms threads=$threads $(server_down)" | tee -a "$RESULTS/soak.txt" ;;
  *)
    awk 'NR > 1 && /^#/ { print; next } NR > 1 { exit }' "$0" >&2
    exit 2 ;;
esac
log "done: $RESULTS"
