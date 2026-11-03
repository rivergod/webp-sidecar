#!/usr/bin/env bash
# glibc 서버 이미지 확인(docs/research/0015-glibc-image-verification.md): Decode RPC 1회당 서버 page fault
# (cgroup memory.stat pgfault)를 이미지별로 센다. Linux, Docker, taskset 필요. 사진 세트와 bench 실행 묶음
# (./gradlew :bench:installBench)이 먼저 있어야 한다.
#
#   bench/glibc-check/faults.sh <출력 tsv> <이름=이미지=seccomp 프로파일>...
#
# 같은 조건을 요청 수 2B와 6B로 두 번 돌려 차이를 4B로 나눈다(연결·첫 요청 같은 고정 비용을 뺀다). JMH 한 번이
# 예열 B회 + 측정 B회를 보낸다. 그 앞에 한 번 더 돌려 서버의 첫 요청과 출력 버퍼 풀 채우기를 뺀다. JMH 출력은
# <출력 tsv>.log에 쌓인다. 서버는 bench/run.sh와 같은 격리 옵션·자원 제한(메모리 1 GiB, CPU 2, CPU 12·14 고정)으로 뜬다.
set -euo pipefail

OUT=$(realpath -m "$1")
shift
cd "$(dirname "$0")/../.."
ROOT=$PWD
LIB="$ROOT/bench/build/install/bench/lib"
JAVA=$(cat "$ROOT/bench/build/install/bench/java-executable.txt")
echo -e "libc\timage_id\tsize\tmode\tbatch\tfaults_2b\tfaults_6b\tfaults_per_op" >"$OUT"
for spec in "$@"; do
  IFS='=' read -r name image seccomp <<<"$spec"
  SOCK=$(mktemp -d /tmp/wpf.XXXX)
  CNAME="wpf-$name-$$"
  docker run -d --rm --name "$CNAME" --user "$(id -u):$(id -g)" \
    --read-only --network none --cap-drop ALL --security-opt no-new-privileges \
    --security-opt "seccomp=$seccomp" \
    --memory 1g --memory-swap 1g --cpus 2 --cpuset-cpus 12,14 \
    -v "$SOCK:/run/webp-imageio" "$image" >/dev/null
  for _ in $(seq 100); do [ -S "$SOCK/webp.sock" ] && break; sleep 0.1; done
  pid=$(docker inspect "$CNAME" --format '{{.State.Pid}}')
  CG=/sys/fs/cgroup$(awk -F: '$1 == "0" { print $3 }' "/proc/$pid/cgroup")
  id=$(docker image inspect --format '{{.Id}}' "$image")
  pgf() { awk '$1 == "pgfault" { print $2 }' "$CG/memory.stat"; }
  jmh() { # jmh <size> <mode> <batch>
    taskset -c 0,2,4,6 "$JAVA" -Xmx1g -cp "$LIB/*" org.openjdk.jmh.Main '\.TransportBenchmark\.decode$' \
      -p server=sse2-uds -p size="$1" -p mode="$2" -bm ss -bs "$3" -wi 1 -i 1 -f 1 \
      -jvmArgsPrepend "-Dwebp.bench.server.sse2-uds=unix://$SOCK/webp.sock -Dwebp.bench.samples=$ROOT/samples" \
      >>"$OUT.log" 2>&1
  }
  for spec2 in M:100 L:50 XL:20; do
    size=${spec2%%:*}
    b=${spec2##*:}
    for mode in lossy alpha lossless; do
      echo "[faults $(date -u +%H:%M:%S)] $name $size $mode" >&2
      jmh "$size" "$mode" "$b" # 서버 쪽 첫 요청·풀 채우기를 뺀다
      f0=$(pgf); jmh "$size" "$mode" "$b"; f1=$(pgf)
      jmh "$size" "$mode" $((b * 3)); f2=$(pgf)
      awk -v n="$name" -v id="$id" -v s="$size" -v m="$mode" -v b="$b" -v a=$((f1 - f0)) -v c=$((f2 - f1)) \
        'BEGIN { printf "%s\t%s\t%s\t%s\t%d\t%d\t%d\t%.0f\n", n, id, s, m, b, a, c, (c - a) / (4 * b) }' >>"$OUT"
    done
  done
  docker rm -f "$CNAME" >/dev/null
  rm -rf "$SOCK"
done
