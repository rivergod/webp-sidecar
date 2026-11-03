#!/usr/bin/env bash
# glibc 서버 이미지 확인(docs/research/0015-glibc-image-verification.md): 할당자를 섞인 부하로 데운 같은 컨테이너에서
# (1) 유휴 뒤 상주 메모리 반환과 (2) ADR-0016 최악 조합의 OOM 여부를 본다. Linux, Docker, taskset, Rust 툴체인 필요.
# 사진 세트와 bench 실행 묶음(./gradlew :bench:installBench)이 먼저 있어야 한다.
#
#   bench/glibc-check/warm-worst.sh <출력 디렉터리> [데우기 분, 기본 20] [이미지] [seccomp 프로파일]
#
# 단계: 유휴 기준선 5초 → 섞인 부하(bench/soak.sh soak과 같은 MixedLoad, JVM 3 × 스레드 4) → 유휴 60초 → 최악 조합
# 다섯 개를 차례로(동시 2, server/tests/memory_budget.rs) → 유휴 60초. 서버 메모리를 1초마다 memory.tsv에, 단계별 요약을
# warm.txt에 남긴다. 서버는 bench/soak.sh와 같은 격리 옵션·자원 제한으로 뜬다.
set -euo pipefail

OUT=$(realpath -m "$1")
WARM_MIN=${2:-20}
cd "$(dirname "$0")/../.."
ROOT=$PWD
IMAGE=${3:-webp-imageio-server:dev}
SECCOMP=${4:-$ROOT/deploy/seccomp/webp-imageio-server.json}
LIB="$ROOT/bench/build/install/bench/lib"
JAVA=$(cat "$ROOT/bench/build/install/bench/java-executable.txt")
mkdir -p "$OUT"
log() { printf '[warm %s] %s\n' "$(date -u +%H:%M:%S)" "$*" >&2; }

SOCK=$(mktemp -d /tmp/wpw.XXXX)
CNAME="wpw-$$"
SAMPLER=""
cleanup() {
  [ -n "$SAMPLER" ] && kill "$SAMPLER" 2>/dev/null || true
  docker rm -f "$CNAME" >/dev/null 2>&1 || true
  rm -rf "$SOCK"
}
trap cleanup EXIT

(cd server && cargo test -q --release --test memory_budget --no-run >/dev/null 2>&1)

# bench/soak.sh server_up과 같은 격리·자원 제한(메모리 1 GiB, CPU 2, 동시 처리 기본 2).
docker run -d --name "$CNAME" --user "$(id -u):$(id -g)" \
  --read-only --network none --cap-drop ALL --security-opt no-new-privileges \
  --security-opt "seccomp=$SECCOMP" \
  --memory 1g --memory-swap 1g --cpus 2 --cpuset-cpus 12,14 --pids-limit 128 \
  -v "$SOCK:/run/webp-imageio" "$IMAGE" >/dev/null
for _ in $(seq 100); do [ -S "$SOCK/webp.sock" ] && break; sleep 0.1; done
[ -S "$SOCK/webp.sock" ] || { docker logs "$CNAME" >&2; exit 1; }
pid=$(docker inspect "$CNAME" --format '{{.State.Pid}}')
CG=/sys/fs/cgroup$(awk -F: '$1 == "0" { print $3 }' "/proc/$pid/cgroup")
{
  echo "date_utc: $(date -u +%Y-%m-%dT%H:%M:%SZ)"
  echo "git: $(git rev-parse HEAD)"
  echo "image: $IMAGE $(docker image inspect --format '{{.Id}}' "$IMAGE")"
  echo "seccomp: $SECCOMP"
  echo "warm_minutes: $WARM_MIN (MixedLoad, JVM 3 x 4 threads, busy-retries 3)"
} >"$OUT/environment.txt"

# 서버 메모리를 1초마다 기록한다: 경과 초, 단계, current, anon MiB.
PHASE_FILE="$OUT/.phase"
echo start >"$PHASE_FILE"
t0=$(date +%s)
echo -e "t_s\tphase\tcurrent_mib\tanon_mib" >"$OUT/memory.tsv"
(
  while [ -f "$CG/memory.current" ]; do
    awk -v t="$(($(date +%s) - t0))" -v ph="$(cat "$PHASE_FILE")" -v cur="$(cat "$CG/memory.current")" \
      '$1 == "anon" { a = $2 } END { printf "%d\t%s\t%.1f\t%.1f\n", t, ph, cur / 1048576, a / 1048576 }' \
      "$CG/memory.stat" >>"$OUT/memory.tsv" 2>/dev/null || true
    sleep 1
  done
) &
SAMPLER=$!

anon() { awk '$1 == "anon" { printf "%.1f", $2 / 1048576 }' "$CG/memory.stat"; }
idle() { # idle <이름> <초>
  echo "$1" >"$PHASE_FILE"
  sleep "$2"
  echo "idle_$1 after_s=$2 anon_mib=$(anon)" | tee -a "$OUT/warm.txt"
}

idle baseline 5

# 1) 데우기: soak.sh와 같은 섞인 부하.
log "warm ${WARM_MIN}min"
echo warm >"$PHASE_FILE"
pids=()
for i in 1 2 3; do
  taskset -c 0,2,4,6 "$JAVA" -Xms1g -Xmx2g -Dwebp.imageio.endpoint="unix://$SOCK/webp.sock" \
    -Dwebp.imageio.busy-retries=3 -Dwebp.bench.samples="$ROOT/samples" \
    -cp "$LIB/*" net.rivergod.webp.imageio.bench.MixedLoad mixed 4 $((WARM_MIN * 60)) 60 \
    >"$OUT/warm-$i.txt" 2>"$OUT/warm-$i.err" &
  pids+=($!)
done
for p in "${pids[@]}"; do wait "$p"; done
echo "warm_end anon_mib=$(anon)" | tee -a "$OUT/warm.txt"

# 2) 유휴: 풀 유휴 만료(30초) + 정리 간격(15초)보다 길게 기다린다(ADR-0018).
idle after_warm 60

# 3) 최악 조합(ADR-0016 확인 조합 중 통과하는 것, 동시 2)을 같은 컨테이너에 차례로 보낸다.
for spec in decode-lossless:8192 decode-lossy:8192 encode-lossless:4096 encode-lossy:4096 decode-anim:4096; do
  op=${spec%%:*}
  side=${spec##*:}
  log "worst $op $side"
  echo "worst-$op-$side" >"$PHASE_FILE"
  before=$(anon)
  if R=$(cd server && WEBP_IMAGEIO_TEST_UDS="$SOCK/webp.sock" WEBP_IMAGEIO_TEST_OP="$op" WEBP_IMAGEIO_TEST_SIDE="$side" \
      WEBP_IMAGEIO_TEST_CONCURRENT=2 cargo test -q --release --test memory_budget -- --ignored 2>&1); then
    result=ok
  else
    result="FAILED($(printf '%s\n' "$R" | sed -n 's/.* failed: \([A-Za-z]*\) .*/\1/p' | head -1))"
  fi
  running=$(docker inspect "$CNAME" --format '{{.State.Running}} oom={{.State.OOMKilled}}' 2>/dev/null || echo gone)
  max=$(awk -F'\t' -v ph="worst-$op-$side" '$2 == ph && $3 > m { m = $3 } END { printf "%.1f", m }' "$OUT/memory.tsv")
  echo "worst op=$op side=$side concurrent=2 anon_before_mib=$before current_max_1s_mib=$max result=$result running=$running" \
    | tee -a "$OUT/warm.txt"
done
peak=$(awk '{ printf "%.0f", $1 / 1048576 }' "$CG/memory.peak" 2>/dev/null || echo "?")
echo "memory_peak_mib(container lifetime)=$peak oom=$(docker inspect "$CNAME" --format '{{.State.OOMKilled}}')" \
  | tee -a "$OUT/warm.txt"

# 4) 다시 유휴.
idle after_worst 60
rm -f "$PHASE_FILE"
log "done: $OUT"
