#!/usr/bin/env bash
# 서버 컨테이너의 격리 옵션이 실제로 적용됐는지 확인한다(Phase 5-1, deploy/README.md "실행 (격리 옵션)").
# 설정값(docker inspect)만이 아니라 호스트에서 본 서버 프로세스 상태(/proc, cgroup)로 확인한다. Linux, Docker 필요.
#
#   deploy/scripts/verify-hardening.sh [이미지]     기본 이미지: webp-imageio-server:dev
#
# 종료 코드: 모두 통과 0, 하나라도 실패 1.
set -euo pipefail
cd "$(dirname "$0")/../.."
IMAGE=${1:-webp-imageio-server:dev}
NAME=webp-hardening-$$
VOLUME=webp-hardening-$$

cleanup() {
  docker rm -f "$NAME" >/dev/null 2>&1 || true
  docker volume rm "$VOLUME" >/dev/null 2>&1 || true
}
trap cleanup EXIT

# deploy/README.md의 실행 명령과 같은 옵션(사용자는 이미지 기본값 65532).
docker run -d --name "$NAME" \
  --read-only --network none --cap-drop ALL --security-opt no-new-privileges \
  --security-opt seccomp=deploy/seccomp/webp-imageio-server.json \
  --memory 1g --memory-swap 1g --cpus 2 --pids-limit 128 \
  -v "$VOLUME:/run/webp-imageio" \
  "$IMAGE" >/dev/null
sleep 1

FAILED=0
check() { # check <설명> <기대값> <실제값>
  if [ "$2" = "$3" ]; then
    printf 'ok    %-44s %s\n' "$1" "$3"
  else
    printf 'FAIL  %-44s expected=%s actual=%s\n' "$1" "$2" "$3"
    FAILED=1
  fi
}

inspect() { docker inspect "$NAME" --format "$1"; }
check "running" "true" "$(inspect '{{.State.Running}}')"

# 1. 설정값
check "inspect: read-only rootfs" "true" "$(inspect '{{.HostConfig.ReadonlyRootfs}}')"
check "inspect: network mode" "none" "$(inspect '{{.HostConfig.NetworkMode}}')"
check "inspect: cap drop" "[ALL]" "$(inspect '{{.HostConfig.CapDrop}}')"
check "inspect: memory (bytes)" "1073741824" "$(inspect '{{.HostConfig.Memory}}')"
check "inspect: memory+swap (bytes)" "1073741824" "$(inspect '{{.HostConfig.MemorySwap}}')"
check "inspect: nano cpus" "2000000000" "$(inspect '{{.HostConfig.NanoCpus}}')"
check "inspect: pids limit" "128" "$(inspect '{{.HostConfig.PidsLimit}}')"
check "inspect: image user" "65532:65532" "$(inspect '{{.Config.User}}')"

# 2. 실제 프로세스 상태(호스트에서 본 서버 프로세스)
PID=$(inspect '{{.State.Pid}}')
status() { awk -v k="$1:" '$1 == k { $1 = ""; sub(/^ /, ""); print }' "/proc/$PID/status"; }
check "proc: uid (real effective saved fs)" "65532 65532 65532 65532" "$(status Uid | tr -s '\t ' ' ')"
check "proc: gid (real effective saved fs)" "65532 65532 65532 65532" "$(status Gid | tr -s '\t ' ' ')"
check "proc: effective capabilities" "0000000000000000" "$(status CapEff)"
check "proc: permitted capabilities" "0000000000000000" "$(status CapPrm)"
check "proc: bounding capabilities" "0000000000000000" "$(status CapBnd)"
check "proc: no_new_privs" "1" "$(status NoNewPrivs)"
check "proc: seccomp mode (2 = filter)" "2" "$(status Seccomp)"
# mountinfo 6번째 필드가 마운트 옵션이다.
check "mount: / read-only" "ro" "$(awk '$5 == "/" { split($6, o, ","); print o[1] }' "/proc/$PID/mountinfo")"
check "mount: /run/webp-imageio writable" "rw" "$(awk '$5 == "/run/webp-imageio" { split($6, o, ","); print o[1] }' "/proc/$PID/mountinfo")"
check "net: interfaces" "lo" "$(awk -F: 'NR > 2 { gsub(/ /, "", $1); print $1 }' "/proc/$PID/net/dev" | tr '\n' ' ' | sed 's/ $//')"

# 3. cgroup v2 제한(서버 프로세스가 속한 cgroup 파일)
CG=/sys/fs/cgroup$(awk -F: '$1 == "0" { print $3 }' "/proc/$PID/cgroup")
check "cgroup: memory.max" "1073741824" "$(cat "$CG/memory.max")"
check "cgroup: memory.swap.max" "0" "$(cat "$CG/memory.swap.max")"
check "cgroup: cpu.max" "200000 100000" "$(cat "$CG/cpu.max")"
check "cgroup: pids.max" "128" "$(cat "$CG/pids.max")"

if [ "$FAILED" -ne 0 ]; then
  echo "hardening check FAILED" >&2
  exit 1
fi
echo "hardening check passed"
