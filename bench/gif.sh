#!/usr/bin/env bash
# GIF → WebP 변환 측정(ADR-0016·0017 결정용). Linux, Docker, taskset 필요. 저장소 루트에서 실행한다.
#
#   bench/gif.sh inputs                 합성 GIF(samples/bench/gif/, 커밋하지 않음). 사진 세트가 먼저 있어야 한다
#   bench/gif.sh single                 파일·모드별 단건 변환: 시간, 출력 크기, 서버 메모리 최고치
#   bench/gif.sh load [GIF]             동시 변환 1/2/4/8/16건 × lossy/lossless(각 LOAD_SECONDS초)
#   bench/gif.sh gif2webp               참고: libwebp gif2webp(도구 이미지)로 같은 파일 변환 시간·크기
#
# 환경변수
#   BENCH_RESULTS   결과 디렉터리(기본 bench/results/<UTC 시각>-gif)
#   GIF_EXTRA       공백으로 구분한 추가 GIF 경로(예: 출처를 커밋할 수 없는 실제 파일). 결과에는 파일 이름과 sha256만 남긴다
#   GIF_SYNTH       합성 GIF 목록을 바꾼다(기본: GifInputs가 만드는 6개)
#   LOAD_SECONDS    부하 단계 하나의 시간(기본 60)
#   LOAD_LEVELS     동시 변환 수 목록(기본 "1 2 4 8 16")
#
# 서버는 측정마다 새 컨테이너로 띄운다: ADR-0012 기본 제한(메모리 1 GiB, CPU 2, 동시 처리 2), deploy/README.md 격리 옵션,
# seccomp 프로파일, CPU 12,14 고정. 서버 메모리 최고치는 컨테이너 cgroup의 memory.peak다. JVM은 CPU 0,2,4,6에 고정한다.
set -euo pipefail

cd "$(dirname "$0")/.."
ROOT=$PWD
LIB="$ROOT/bench/build/install/bench/lib"
IMAGE=webp-imageio-server:dev
TOOLS_IMAGE=webp-samples-tools:libwebp-1.6.0
RESULTS=${BENCH_RESULTS:-bench/results/$(date -u +%Y%m%dT%H%M%SZ)-gif}
LOAD_SECONDS=${LOAD_SECONDS:-60}
LOAD_LEVELS=${LOAD_LEVELS:-1 2 4 8 16}
DEFAULT_SYNTH="samples/bench/gif/video_572x315_111.gif samples/bench/gif/video_480x270_93.gif
  samples/bench/gif/video_480x270_10.gif samples/bench/gif/video_480x270_100.gif
  samples/bench/gif/video_480x270_300.gif samples/bench/gif/video_480x270_1000.gif"
read -r -a SYNTH <<<"$(echo ${GIF_SYNTH:-$DEFAULT_SYNTH})"
read -r -a EXTRA <<<"${GIF_EXTRA:-}"

log() { printf '[gif %s] %s\n' "$(date -u +%H:%M:%S)" "$*" >&2; }

install_bench() {
  ./gradlew -q --console=plain :bench:installBench
  JAVA=$(cat "$ROOT/bench/build/install/bench/java-executable.txt")
}

CNAME=""
SOCK=""
cleanup() {
  [ -n "$CNAME" ] && docker rm -f "$CNAME" >/dev/null 2>&1 || true
  [ -n "$SOCK" ] && rm -rf "$SOCK" || true
}
trap cleanup EXIT

server_up() {
  SOCK=$(mktemp -d /tmp/wpg.XXXX)
  CNAME="wpg-$$-$RANDOM"
  docker run -d --name "$CNAME" --user "$(id -u):$(id -g)" \
    --read-only --network none --cap-drop ALL --security-opt no-new-privileges \
    --security-opt "seccomp=$ROOT/deploy/seccomp/webp-imageio-server.json" \
    --memory 1g --memory-swap 1g --cpus 2 --cpuset-cpus 12,14 \
    -v "$SOCK:/run/webp-imageio" "$IMAGE" >/dev/null
  for _ in $(seq 100); do [ -S "$SOCK/webp.sock" ] && break; sleep 0.1; done
  [ -S "$SOCK/webp.sock" ] || { docker logs "$CNAME" >&2; exit 1; }
  local pid
  pid=$(docker inspect "$CNAME" --format '{{.State.Pid}}')
  CGROUP=/sys/fs/cgroup$(awk -F: '$1 == "0" { print $3 }' "/proc/$pid/cgroup")
}

# 서버 메모리 최고치(MiB)와 OOM 여부를 출력하고 컨테이너를 지운다.
server_down() {
  local peak oom
  peak=$(awk '{ printf "%.0f", $1 / 1048576 }' "$CGROUP/memory.peak" 2>/dev/null || echo "?")
  oom=$(docker inspect "$CNAME" --format '{{.State.OOMKilled}}')
  docker rm -f "$CNAME" >/dev/null
  rm -rf "$SOCK"
  CNAME=""
  SOCK=""
  printf 'server_peak_mib=%s oom_killed=%s' "$peak" "$oom"
}

bench_java() { # bench_java <데드라인 ms> <GifBench 인자...>
  local deadline=$1
  shift
  taskset -c 0,2,4,6 "$JAVA" -Xms4g -Xmx8g -Dwebp.imageio.endpoint="unix://$SOCK/webp.sock" \
    -Dwebp.imageio.deadline-ms="$deadline" -cp "$LIB/*" net.rivergod.webp.imageio.bench.GifBench "$@"
}

record_inputs() {
  {
    echo "date_utc: $(date -u +%Y-%m-%dT%H:%M:%SZ)"
    echo "git: $(git rev-parse HEAD)$(git diff --quiet HEAD -- . ':!bench/results' || echo ' (dirty)')"
    echo "image: $(docker image inspect --format '{{.Id}}' "$IMAGE")"
    echo "java: $("$JAVA" -version 2>&1 | head -1)"
    for f in "${SYNTH[@]}" "${EXTRA[@]}"; do
      [ -f "$f" ] && echo "input: $(basename "$f") $(stat -c %s "$f") $(sha256sum "$f" | cut -d' ' -f1)"
    done
  } >"$RESULTS/environment.txt"
}

cmd=${1:-}
[ "$#" -gt 0 ] && shift
case "$cmd" in
  inputs)
    install_bench
    "$JAVA" -cp "$LIB/*" net.rivergod.webp.imageio.bench.GifInputs samples ;;
  single)
    install_bench
    mkdir -p "$RESULTS"
    record_inputs
    out="$RESULTS/single.tsv"
    echo -e "file\tmode\tcanvas\tframes\tgif_bytes\tcomposite_ms\tencode_ms\twebp_bytes\twebp_vs_gif\tserver" >"$out"
    for mode in lossy lossless; do
      for f in "${EXTRA[@]}" "${SYNTH[@]}"; do
        log "single $mode $(basename "$f")"
        server_up
        # 단건은 실제 소요 시간을 보려고 데드라인을 넉넉히 준다(10분).
        line=$(bench_java 600000 single "$mode" "$f" | tail -1 || echo "$(basename "$f")	$mode	FAILED")
        echo -e "$line\t$(server_down)" | tee -a "$out"
      done
    done ;;
  load)
    install_bench
    mkdir -p "$RESULTS"
    record_inputs
    gif=${1:-${EXTRA[0]:-${SYNTH[0]}}}
    out="$RESULTS/load.txt"
    echo "input: $(basename "$gif") (클라이언트 데드라인 기본 30000 ms, 단계당 ${LOAD_SECONDS}초)" >>"$out"
    for mode in lossy lossless; do
      for c in $LOAD_LEVELS; do
        log "load $mode concurrency=$c"
        server_up
        # 부하는 플러그인 기본 데드라인(30초)으로 잰다(실제 사용 조건).
        line=$(bench_java 30000 load "$mode" "$c" "$LOAD_SECONDS" "$gif" | tail -1)
        echo "$line $(server_down)" | tee -a "$out"
      done
    done ;;
  gif2webp)
    mkdir -p "$RESULTS"
    out="$RESULTS/gif2webp.tsv"
    echo -e "file\tmode\tms\twebp_bytes" >"$out"
    for f in "${EXTRA[@]}" "${SYNTH[@]}"; do
      dir=$(cd "$(dirname "$f")" && pwd)
      name=$(basename "$f")
      for mode in lossy lossless; do
        opts=$([ "$mode" = lossy ] && echo "-lossy -q 75" || echo "")
        log "gif2webp $mode $name"
        # 도구 이미지 안에서 한 번 실행한 시간(ms)과 출력 크기. 단일 스레드(gif2webp 기본).
        res=$(docker run --rm --network none --cpuset-cpus 12 -u "$(id -u):$(id -g)" -v "$dir:/in:ro" "$TOOLS_IMAGE" \
          sh -c "s=\$(date +%s%N); gif2webp -quiet $opts /in/$name -o /tmp/o.webp; e=\$(date +%s%N); echo \$(( (e - s) / 1000000 )) \$(stat -c %s /tmp/o.webp)")
        echo -e "$name\t$mode\t${res% *}\t${res#* }" | tee -a "$out"
      done
    done ;;
  *)
    awk 'NR > 1 && /^#/ { print; next } NR > 1 { exit }' "$0" >&2
    exit 2 ;;
esac
