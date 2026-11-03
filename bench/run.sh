#!/usr/bin/env bash
# Phase 4 벤치마크를 한 명령으로 재현한다(ADR-0015). Linux, Docker, taskset(util-linux) 필요.
#
#   bench/run.sh                전체: images → inputs → verify → JMH 모든 묶음 → 요약
#   bench/run.sh images         서버 이미지 두 개(기본 SSE2 빌드, AVX2 빌드)
#   bench/run.sh inputs         입력 세트(samples/bench/, 커밋하지 않음)
#   bench/run.sh verify         서버를 띄우고 검증 테스트(비교 대상 간 픽셀 일치, 표준 메타데이터 비교)
#   bench/run.sh jmh <묶음>...  서버를 띄우고 지정한 JMH 묶음만. 묶음 이름은 아래 suite_args
#   bench/run.sh decode-time    서버 안 libwebp 디코딩 시간(gRPC 없음, server/examples/decode_time.rs). 비용 분해용
#   bench/run.sh smoke          모든 묶음을 아주 짧게(fork 1, 측정 1회) 돌려 연결만 확인한다(수치는 쓰지 않는다)
#   bench/run.sh summary <결과 디렉터리>   JSON 결과에서 요약 표를 다시 만든다
#
# 환경변수
#   BENCH_RESULTS      결과 디렉터리(기본 bench/results/<UTC 시각>)
#   BENCH_JVM_CPUS     JMH JVM을 고정할 CPU(기본 0,2,4,6: P코어 4개, HT 형제는 비운다)
#   BENCH_SERVER_CPUS  서버 컨테이너를 고정할 CPU(기본 12,14: P코어 2개). --cpus 2(ADR-0012 기본값)와 함께 쓴다
#   BENCH_TCP_PORT     TCP 측정 서버 포트(기본 50061, 127.0.0.1)
#
# 그 밖에 필요한 것: Rust 툴체인(server/rust-toolchain.toml, decode-time 단계).
#
# 기본 CPU 값은 측정 호스트(i9-14900: P코어 CPU 0-15, E코어 16-31)에 맞춘 것이다. 다른 호스트에서는 lscpu -e로 확인해 바꾼다.
# JVM과 서버를 같은 최대 클럭(5.5 GHz) P코어에 따로 고정해 하이브리드 CPU의 코어 배정 차이를 없앤다.
set -euo pipefail

cd "$(dirname "$0")/.."
ROOT=$PWD
LIB="$ROOT/bench/build/install/bench/lib"
JVM_CPUS=${BENCH_JVM_CPUS:-0,2,4,6}
SERVER_CPUS=${BENCH_SERVER_CPUS:-12,14}
TCP_PORT=${BENCH_TCP_PORT:-50061}
IMAGE_SSE2=webp-imageio-server:dev
IMAGE_AVX2=webp-imageio-server:dev-avx2
TOOLS_IMAGE=webp-samples-tools:libwebp-1.6.0

log() { printf '[bench %s] %s\n' "$(date -u +%H:%M:%S)" "$*" >&2; }

build_images() {
  log "server images"
  docker build -q -f deploy/Dockerfile -t "$IMAGE_SSE2" . >/dev/null
  docker build -q -f deploy/Dockerfile --build-arg CARGO_FEATURES=simd-avx2 -t "$IMAGE_AVX2" . >/dev/null
}

install_bench() {
  log "gradle :bench:installBench"
  ./gradlew -q --console=plain :bench:installBench
  JAVA=$(cat "$ROOT/bench/build/install/bench/java-executable.txt")
}

build_inputs() {
  install_bench
  log "inputs: synthetic (samples tool image)"
  docker build -q -t "$TOOLS_IMAGE" samples/scripts >/dev/null
  local run=(docker run --rm --network none -u "$(id -u):$(id -g)" -v "$ROOT:/repo" -w /repo "$TOOLS_IMAGE")
  "${run[@]}" sh samples/scripts/build.sh bench
  log "inputs: photo sources (download + sha256 + resize)"
  "$JAVA" -cp "$LIB/*" net.rivergod.webp.imageio.bench.PhotoSources samples
  log "inputs: photo encode (samples tool image)"
  "${run[@]}" sh samples/scripts/build.sh bench-photo
}

# --- 서버 컨테이너 ---
SOCK_ROOT=""
CONTAINERS=()
SERVER_PROPS=""

cleanup() {
  if [ "${#CONTAINERS[@]}" -gt 0 ]; then
    docker rm -f "${CONTAINERS[@]}" >/dev/null 2>&1 || true
  fi
  if [ -n "$SOCK_ROOT" ]; then
    rm -rf "$SOCK_ROOT"
  fi
}
trap cleanup EXIT

# start_server <이름> <이미지> [docker run 옵션...]
# 격리 옵션은 deploy/README.md와 같다(read-only, cap-drop ALL, no-new-privileges, seccomp, 메모리 1 GiB, CPU 2).
start_server() {
  local name=$1 image=$2
  shift 2
  local dir="$SOCK_ROOT/$name"
  mkdir -p "$dir"
  local cname="wpb-$name-$$"
  docker run -d --rm --name "$cname" --user "$(id -u):$(id -g)" \
    --read-only --cap-drop ALL --security-opt no-new-privileges \
    --security-opt "seccomp=$ROOT/deploy/seccomp/webp-imageio-server.json" \
    --memory 1g --memory-swap 1g --cpus 2 --cpuset-cpus "$SERVER_CPUS" \
    -v "$dir:/run/webp-imageio" "$@" "$image" >/dev/null
  CONTAINERS+=("$cname")
  for _ in $(seq 100); do
    [ -S "$dir/webp.sock" ] && break
    sleep 0.1
  done
  [ -S "$dir/webp.sock" ] || { docker logs "$cname" >&2; log "server $name did not start"; exit 1; }
  SERVER_PROPS="$SERVER_PROPS -Dwebp.bench.server.$name-uds=unix://$dir/webp.sock"
}

start_servers() {
  # AF_UNIX 경로 길이 한도(108바이트) 때문에 짧은 /tmp 경로를 쓴다.
  SOCK_ROOT=$(mktemp -d /tmp/wpb.XXXX)
  log "servers (cpuset $SERVER_CPUS)"
  # 판정용 기본 빌드. TCP는 파드 안 사이드카처럼 같은 네트워크 네임스페이스의 루프백으로 잰다(docker-proxy를 거치지 않는다).
  start_server sse2 "$IMAGE_SSE2" --network host -e "WEBP_SERVER_TCP_ADDR=127.0.0.1:$TCP_PORT"
  SERVER_PROPS="$SERVER_PROPS -Dwebp.bench.server.sse2-tcp=127.0.0.1:$TCP_PORT"
  start_server avx2 "$IMAGE_AVX2" --network none
  # 청크 크기 비교(ADR-0003). 서버 응답 조각 크기 = WEBP_SERVER_MAX_CHUNK_BYTES.
  start_server sse2-256k "$IMAGE_SSE2" --network none -e WEBP_SERVER_MAX_CHUNK_BYTES=262144
  start_server sse2-512k "$IMAGE_SSE2" --network none -e WEBP_SERVER_MAX_CHUNK_BYTES=524288
  start_server sse2-2m "$IMAGE_SSE2" --network none -e WEBP_SERVER_MAX_CHUNK_BYTES=2097152
  # 이름 규칙(BenchServers): sse2-uds, avx2-uds, sse2-uds-256k ...
  SERVER_PROPS=${SERVER_PROPS//sse2-256k-uds/sse2-uds-256k}
  SERVER_PROPS=${SERVER_PROPS//sse2-512k-uds/sse2-uds-512k}
  SERVER_PROPS=${SERVER_PROPS//sse2-2m-uds/sse2-uds-2m}
}

# --- 결과와 환경 기록 ---
results_dir() {
  RESULTS=${BENCH_RESULTS:-bench/results/$(date -u +%Y%m%dT%H%M%SZ)}
  mkdir -p "$RESULTS"
  RESULTS=$(cd "$RESULTS" && pwd)
}

record_environment() {
  local f="$RESULTS/environment.txt"
  {
    echo "date_utc: $(date -u +%Y-%m-%dT%H:%M:%SZ)"
    echo "git: $(git rev-parse HEAD)$(git diff --quiet HEAD -- . ':!bench/results' || echo ' (dirty)')"
    echo "cpu: $(lscpu | sed -n 's/^Model name:[[:space:]]*//p')"
    echo "cpus: $(nproc), jvm_cpus: $JVM_CPUS, server_cpus: $SERVER_CPUS (--cpus 2, --memory 1g)"
    echo "governor: $(cat /sys/devices/system/cpu/cpu0/cpufreq/scaling_governor 2>/dev/null || echo unknown)"
    echo "no_turbo: $(cat /sys/devices/system/cpu/intel_pstate/no_turbo 2>/dev/null || echo unknown)"
    echo "memory: $(free -g | awk '/^Mem:/ {print $2 " GiB"}')"
    echo "kernel: $(uname -r)"
    echo "os: $(. /etc/os-release && echo "$PRETTY_NAME")"
    echo "java: $("$JAVA" -version 2>&1 | tr '\n' ' ')"
    echo "docker: $(docker version --format '{{.Server.Version}}')"
    echo "image_sse2: $(docker image inspect --format '{{.Id}}' "$IMAGE_SSE2")"
    echo "image_avx2: $(docker image inspect --format '{{.Id}}' "$IMAGE_AVX2")"
    echo "wasm: $(sha256sum bench/wasm/build/libwebp.wasm | cut -d' ' -f1) (libwebp 1.6.0, wasi-sdk 34, -O3, no SIMD, no threads)"
    echo "jmh_jars: $(cd "$LIB" && ls jmh-core-*.jar imageio-webp-*.jar webp-imageio-*.jar runtime-*.jar | tr '\n' ' ')"
    echo "photo: $(sed -n 's/.*"sha256": "\(.*\)".*/\1/p' samples/bench.lock.json)"
  } >"$f"
  log "environment -> $f"
}

# --- JMH 묶음 ---
suite_args() {
  case "$1" in
    decode) echo '\.DecodeBenchmark\. -p impl=SIDECAR,TWELVEMONKEYS,DARKXANTER,WASM -p server=sse2-uds' ;;
    # 비교 기준(sse2-uds)을 같은 묶음에 넣는다. 다른 묶음의 값과 비교하면 시간에 따른 흔들림이 섞인다.
    decode-servers) echo '\.DecodeBenchmark\. -p impl=SIDECAR -p server=sse2-uds,sse2-tcp,avx2-uds -p content=photo' ;;
    decode-chunk) echo '\.DecodeBenchmark\. -p impl=SIDECAR -p server=sse2-uds-256k,sse2-uds-512k,sse2-uds,sse2-uds-2m -p content=photo -p size=M,L,XL -p mode=lossy,lossless' ;;
    decode-wasm-interp) echo '\.DecodeBenchmark\. -p impl=WASM_INTERPRETER -p content=photo -p size=XS,S,M' ;;
    transport) echo '\.TransportBenchmark\.decode$ -p server=sse2-uds,sse2-tcp -p mode=lossy,lossless' ;;
    features) echo '\.TransportBenchmark\.getFeatures$ -p server=sse2-uds -p mode=lossy,lossless' ;;
    conversion) echo '\.ConversionBenchmark\.' ;;
    encode) echo '\.EncodeBenchmark\.' ;;
    animation) echo '\.AnimationBenchmark\.' ;;
    *) log "unknown suite: $1"; exit 2 ;;
  esac
}
ALL_SUITES=(decode decode-servers decode-chunk decode-wasm-interp transport features conversion encode animation)

run_suite() {
  local suite=$1
  shift
  # shellcheck disable=SC2046
  log "jmh $suite"
  taskset -c "$JVM_CPUS" "$JAVA" -Xmx1g -cp "$LIB/*" org.openjdk.jmh.Main $(suite_args "$suite") \
    -jvmArgsPrepend "$SERVER_PROPS -Dwebp.bench.samples=$ROOT/samples" \
    "$@" -rf json -rff "$RESULTS/$suite.json" -o "$RESULTS/$suite.txt"
}

# 서버 이미지와 같은 glibc 할당자(ADR-0021, 호스트 glibc), 같은 libwebp 빌드(기본/AVX2)로 디코딩만 잰다.
# 서버는 출력 버퍼를 풀로 재사용하므로(ADR-0018) 같은 버퍼를 재사용해 잰다(DECODE_TIME_REUSE=1). 새로 할당하면
# M lossy가 musl 19%, glibc XL lossy 27% 느려 서버 값과 달라진다(research/0015). 서버 CPU 하나에 고정한다.
decode_time() {
  local cpu=${SERVER_CPUS%%,*}
  local files=()
  for size in XS_64x64 S_256x256 M_1024x768 L_1920x1080 XL_4000x3000; do
    for mode in lossy_q75 lossless lossy_alpha_q75; do
      files+=("$ROOT/samples/bench/photo/${size}_${mode}.webp")
    done
  done
  for build in sse2 avx2; do
    local feat=() dir="$ROOT/server/target"
    if [ "$build" = avx2 ]; then feat=(--features simd-avx2); dir="$ROOT/server/target/avx2"; fi
    log "decode-time ($build)"
    (cd server && cargo build -q --release --locked --target x86_64-unknown-linux-gnu --example decode_time \
      --target-dir "$dir" "${feat[@]}")
    DECODE_TIME_REUSE=1 taskset -c "$cpu" "$dir/x86_64-unknown-linux-gnu/release/examples/decode_time" 50 "${files[@]}" \
      | sed "s|$ROOT/samples/bench/photo/||" >"$RESULTS/decode-time-$build.tsv"
  done
}

summary() {
  python3 -I bench/scripts/summarize.py "$1"
}

verify() {
  log "verify (gradle :bench:test)"
  local endpoint=${SERVER_PROPS#* -Dwebp.bench.server.sse2-uds=}
  endpoint=${endpoint%% *}
  ./gradlew -q --console=plain :bench:test -Pwebp.bench.endpoint="$endpoint" -Pwebp.bench.report="$RESULTS/verify"
}

cmd=${1:-all}
[ "$#" -gt 0 ] && shift
case "$cmd" in
  images) build_images ;;
  inputs) build_inputs ;;
  verify)
    install_bench; results_dir; start_servers; verify ;;
  jmh)
    install_bench; results_dir; start_servers; record_environment
    for s in "$@"; do run_suite "$s"; done
    summary "$RESULTS" ;;
  decode-time)
    results_dir; decode_time ;;
  smoke)
    install_bench; results_dir; start_servers
    for s in "${ALL_SUITES[@]}"; do run_suite "$s" -f 1 -wi 0 -i 1 -r 100ms; done ;;
  summary) summary "$1" ;;
  all)
    build_images; build_inputs; results_dir; start_servers; record_environment; verify
    for s in "${ALL_SUITES[@]}"; do run_suite "$s"; done
    decode_time
    summary "$RESULTS" ;;
  *) awk 'NR > 1 && /^#/ { print; next } NR > 1 { exit }' "$0" >&2; exit 2 ;;
esac
