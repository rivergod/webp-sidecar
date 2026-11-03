#!/usr/bin/env python3
"""libwebp-test-data를 고정 커밋으로 받아 samples/conformance/에 둔다(커밋하지 않음, ADR-0006).

    python3 -I samples/scripts/fetch_conformance.py                 # 받기 + 검증 + 설치
    python3 -I samples/scripts/fetch_conformance.py --verify-only   # 이미 받은 파일만 검증(네트워크 없음)
    python3 -I samples/scripts/fetch_conformance.py --check-pam-md5 # + dwebp -pam md5 비교(도구 이미지)
    python3 -I samples/scripts/fetch_conformance.py --write-lock    # 잠금 파일을 처음 만들 때만

- 출처: https://chromium.googlesource.com/webm/libwebp-test-data 커밋 06ddd96e276c2c638a72d39d3c0f340afd61978c
  의 gitiles tar.gz. 압축 파일 자체의 바이트는 서버가 그때그때 만들어 고정되지 않으므로, 풀어 낸 파일마다
  samples/conformance.lock.json의 sha256과 비교한다. 파일이 하나라도 빠지거나, 남거나, 다르면 설치하지
  않고 종료 코드 1로 실패한다.
- 내려받은 데이터는 신뢰하지 않는다. 새 임시 디렉터리에 받고, 일반 파일 외의 tar 항목(링크, 장치,
  절대 경로, '..')이 있으면 거부하고, 아무것도 실행하지 않는다.
- 라이선스: 저장소에 LICENSE 파일이 없다(미확인, BSD-3-Clause 추정 — research/0005). 그래서 커밋하지 않는다.
- 비교 규칙: libwebp_tests.md5에서는 '.pam' 줄만 쓴다. '.pgm' 줄은 아직 릴리스되지 않은 libwebp 변경
  (커밋 0d14d84b)으로 갱신돼 있어 1.6.0과 맞지 않는다(research/0005). PAM은 dwebp -pam 출력
  ('P7\\nWIDTH w\\nHEIGHT h\\nDEPTH 4\\nMAXVAL 255\\nTUPLTYPE RGB_ALPHA\\nENDHDR\\n' + RGBA8)이다.
"""

from __future__ import annotations

import argparse
import hashlib
import io
import json
import shutil
import subprocess
import sys
import tarfile
import tempfile
import urllib.request
from pathlib import Path

sys.dont_write_bytecode = True
sys.path.insert(0, str(Path(__file__).resolve().parent))

import sampleslib as sl  # noqa: E402

REPO_URL = "https://chromium.googlesource.com/webm/libwebp-test-data"
COMMIT = "06ddd96e276c2c638a72d39d3c0f340afd61978c"
ARCHIVE_URL = f"{REPO_URL}/+archive/{COMMIT}.tar.gz"
LOCK = sl.SAMPLES_DIR / "conformance.lock.json"
DEST = sl.SAMPLES_DIR / "conformance"
MAX_ARCHIVE_BYTES = 64 * 1024 * 1024  # 실제 약 20 MB. 비정상적으로 크면 받다가 멈춘다.


def download(url: str) -> bytes:
    """URL을 메모리로 받는다. 크기 상한을 넘으면 실패한다."""
    req = urllib.request.Request(url, headers={"User-Agent": "webp-sidecar-samples-fetch/1"})
    with urllib.request.urlopen(req, timeout=120) as resp:
        data = resp.read(MAX_ARCHIVE_BYTES + 1)
    if len(data) > MAX_ARCHIVE_BYTES:
        raise SystemExit(f"archive larger than {MAX_ARCHIVE_BYTES} bytes, refusing")
    return data


def safe_extract(archive: bytes, dest: Path) -> None:
    """일반 파일과 디렉터리만 허용하고 풀어 낸다(경로 탈출·링크 거부)."""
    with tarfile.open(fileobj=io.BytesIO(archive), mode="r:gz") as tar:
        members = tar.getmembers()
        for m in members:
            name = Path(m.name)
            if not (m.isfile() or m.isdir()) or name.is_absolute() or ".." in name.parts:
                raise SystemExit(f"unsafe archive member rejected: {m.name!r}")
        # Python 3.12+의 'data' 필터가 권한·소유자도 정리한다. 없는 버전에서는 위 검사로 대신한다.
        if hasattr(tarfile, "data_filter"):
            tar.extractall(dest, members=members, filter="data")
        else:  # pragma: no cover - 오래된 파이썬
            tar.extractall(dest, members=members)


def hash_tree(root: Path) -> dict[str, str]:
    """root 아래 모든 파일의 POSIX 상대 경로 -> sha256."""
    out = {}
    for p in sorted(root.rglob("*")):
        if p.is_symlink():
            raise SystemExit(f"symlink found: {p}")
        if p.is_file():
            out[p.relative_to(root).as_posix()] = sl.sha256_file(p)
    return out


def load_lock() -> dict:
    if not LOCK.exists():
        raise SystemExit(f"{sl.rel(LOCK)} missing; run with --write-lock once (maintainers only)")
    with open(LOCK, encoding="utf-8") as f:
        lock = json.load(f)
    if lock["commit"] != COMMIT:
        raise SystemExit("lock file commit does not match the pinned commit")
    return lock


def compare(actual: dict[str, str], expected: dict[str, str]) -> list[str]:
    errors = []
    for path in sorted(set(expected) - set(actual)):
        errors.append(f"missing: {path}")
    for path in sorted(set(actual) - set(expected)):
        errors.append(f"unexpected: {path}")
    for path in sorted(set(actual) & set(expected)):
        if actual[path] != expected[path]:
            errors.append(f"sha256 mismatch: {path}")
    return errors


def fail(errors: list[str]) -> None:
    for e in errors:
        print(f"ERROR: {e}", file=sys.stderr)
    raise SystemExit(f"conformance data verification failed ({len(errors)} problem(s))")


def check_pam_md5(root: Path) -> None:
    """libwebp_tests.md5의 '.pam' 줄을 이 환경의 dwebp(-pam)로 다시 계산해 비교한다."""
    version = sl.tool_versions("dwebp")
    lines = (root / "libwebp_tests.md5").read_text(encoding="ascii").splitlines()
    pam = [ln.split() for ln in lines if ln.strip().endswith(".pam")]
    tmp = Path(tempfile.mkdtemp(prefix="webp-pam-"))
    failed = []
    try:
        for digest, name in pam:
            src = root / name[:-len(".pam")]
            out = tmp / "out.pam"
            proc = subprocess.run(["dwebp", "-quiet", "-pam", str(src), "-o", str(out)],
                                  stdout=subprocess.PIPE, stderr=subprocess.PIPE)
            if proc.returncode != 0 or hashlib.md5(out.read_bytes()).hexdigest() != digest:
                failed.append(name)
    finally:
        shutil.rmtree(tmp)
    print(f"pam md5 (dwebp {sorted(version)}): {len(pam) - len(failed)}/{len(pam)} match")
    if failed:
        fail([f"pam md5 mismatch: {n}" for n in failed])


def main() -> None:
    ap = argparse.ArgumentParser(description="fetch libwebp-test-data pinned by commit and sha256")
    ap.add_argument("--verify-only", action="store_true", help="verify samples/conformance/ only")
    ap.add_argument("--check-pam-md5", action="store_true",
                    help="also compare dwebp -pam output with the .pam lines of libwebp_tests.md5")
    ap.add_argument("--write-lock", action="store_true",
                    help="(maintainers) create conformance.lock.json from a fresh download")
    args = ap.parse_args()

    if args.verify_only:
        errors = compare(hash_tree(DEST), load_lock()["files"])
        if errors:
            fail(errors)
        print(f"conformance: {sl.rel(DEST)} matches {sl.rel(LOCK)}")
        if args.check_pam_md5:
            check_pam_md5(DEST)
        return

    # 새 빈 임시 디렉터리(저장소 밖)에 받고 풀어서 검증한 뒤에만 옮긴다.
    work = Path(tempfile.mkdtemp(prefix="webp-conformance-"))
    try:
        print(f"downloading {ARCHIVE_URL}")
        archive = download(ARCHIVE_URL)
        extracted = work / "tree"
        extracted.mkdir()
        safe_extract(archive, extracted)
        actual = hash_tree(extracted)
        if args.write_lock:
            lock = {
                "repo": REPO_URL,
                "commit": COMMIT,
                "archive_url": ARCHIVE_URL,
                "note": "per-file sha256 of the extracted tree; the archive bytes are not stable",
                "file_count": len(actual),
                "total_bytes": sum((extracted / p).stat().st_size for p in actual),
                "files": actual,
            }
            sl.write_text(LOCK, sl.dump_json(lock))
            print(f"wrote {sl.rel(LOCK)} ({len(actual)} files)")
        errors = compare(actual, load_lock()["files"])
        if errors:
            fail(errors)
        if DEST.exists():
            shutil.rmtree(DEST)
        shutil.move(str(extracted), str(DEST))
        print(f"conformance: {len(actual)} files verified -> {sl.rel(DEST)}")
    finally:
        shutil.rmtree(work, ignore_errors=True)
    if args.check_pam_md5:
        check_pam_md5(DEST)


if __name__ == "__main__":
    main()
