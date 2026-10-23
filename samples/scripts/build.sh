#!/bin/sh
# 샘플 세트를 다시 만드는 단일 진입점 (ADR-0006).
# 도구 이미지(samples/scripts/Dockerfile, libwebp 1.6.0) 안에서 실행한다. 사용법은 samples/README.md.
#
#   build.sh [all]                      generate -> tamper -> expected -> manifest -> check
#   build.sh check                      manifest.json / SOURCES.md / 커밋 기준 검사만
#   build.sh bench [--xxl]              벤치 합성 세트(samples/bench/, 커밋하지 않음)
#   build.sh conformance [옵션]         libwebp-test-data 받기(samples/conformance/, 네트워크 필요)
set -eu

# 저장소 루트에서 실행한다. 스크립트는 모두 자기 위치 기준으로 samples/를 찾는다.
cd "$(dirname "$0")/../.."

cmd="${1:-all}"
[ "$#" -gt 0 ] && shift
case "$cmd" in
  all)
    python3 -I samples/scripts/generate.py
    python3 -I samples/scripts/tamper.py
    python3 -I samples/scripts/expected.py
    python3 -I samples/scripts/manifest.py
    python3 -I samples/scripts/manifest.py --check
    ;;
  check)
    python3 -I samples/scripts/manifest.py --check
    ;;
  bench)
    python3 -I samples/scripts/bench_synth.py "$@"
    ;;
  conformance)
    python3 -I samples/scripts/fetch_conformance.py "$@"
    ;;
  *)
    echo "usage: build.sh [all|check|bench [--xxl]|conformance [--verify-only] [--check-pam-md5]]" >&2
    exit 2
    ;;
esac
