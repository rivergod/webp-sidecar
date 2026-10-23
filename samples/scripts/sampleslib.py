"""samples/scripts 공용 도우미 (표준 라이브러리만 사용).

생성(generate.py), 변조(tamper.py), 기대값(expected.py), manifest(manifest.py),
벤치(bench_synth.py) 스크립트가 함께 쓰는 PNG 작성기, RIFF/WebP 청크 도구, 해시,
외부 도구 실행, 중간 기록(.build/*.json) 입출력을 모은다.

`python3 -I`로 실행하면 스크립트 디렉터리가 sys.path에 들어가지 않으므로, 각 스크립트는
자기 디렉터리를 명시적으로 넣은 뒤 이 모듈을 import한다(우리 저장소의 코드만 대상이다).
"""

from __future__ import annotations

import hashlib
import json
import re
import shlex
import struct
import subprocess
import sys
import zlib
from pathlib import Path

# ---------------------------------------------------------------------------
# 경로와 정책 상수
# ---------------------------------------------------------------------------

# samples/ 디렉터리. 모든 기록 경로는 여기 기준 상대 경로(POSIX 구분자)다.
SAMPLES_DIR = Path(__file__).resolve().parent.parent
# 단계 사이에 넘기는 중간 기록. 커밋하지 않는다(samples/.gitignore).
BUILD_DIR = SAMPLES_DIR / ".build"

# ADR-0008: 서버와 같은 libwebp 버전으로만 커밋 출력을 만든다.
LIBWEBP_VERSION = "1.6.0"
LIBWEBP_TARBALL_SHA256 = "e4ab7009bf0629fd11982d4c2aa83964cf244cffba7347ecd39019a9e38c4564"
TOOL_IMAGE_BASE = (
    "debian:trixie-20261020-slim@sha256:"
    "62bd423aeee912639ca8ee681c2b762cb3e25137e0a92b20be455898a9f034ad"
)

# 자체 생성 파일의 라이선스는 프로젝트 라이선스를 따른다(ADR-0009).
SELF_LICENSE = "Apache-2.0"

# ADR-0006 커밋 기준.
MAX_FILE_BYTES = 64 * 1024
MAX_TOTAL_BYTES = 5 * 1024 * 1024

# 기대값 계산에 쓰는 결정적 시드. 바꾸면 모든 생성물이 바뀐다.
DEFAULT_SEED = b"webp-imageio-samples/v1"


def rel(path: Path) -> str:
    """samples/ 기준 POSIX 상대 경로. manifest와 명령 기록에 쓴다."""
    return Path(path).resolve().relative_to(SAMPLES_DIR).as_posix()


def ensure_parent(path: Path) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)


def write_bytes(path: Path, data: bytes) -> None:
    ensure_parent(path)
    path.write_bytes(data)


def sha256_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def sha256_file(path: Path) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for block in iter(lambda: f.read(1 << 20), b""):
            h.update(block)
    return h.hexdigest()


# ---------------------------------------------------------------------------
# 결정적 의사 난수
# ---------------------------------------------------------------------------


def noise_bytes(seed: bytes, label: str, length: int) -> bytes:
    """SHAKE-256(seed || label)의 처음 length 바이트.

    random 모듈은 파이썬 버전 사이에 수열이 같다는 보장이 random()에만 있다.
    SHAKE-256은 FIPS 202로 고정된 함수라 어디서나 같은 바이트를 낸다.
    """
    return hashlib.shake_256(seed + b"\x00" + label.encode("utf-8")).digest(length)


# ---------------------------------------------------------------------------
# PNG 작성기 (zlib + struct)
# ---------------------------------------------------------------------------


def _png_chunk(tag: bytes, data: bytes) -> bytes:
    body = tag + data
    return struct.pack(">I", len(data)) + body + struct.pack(">I", zlib.crc32(body) & 0xFFFFFFFF)


def encode_png(width: int, height: int, rgba: bytes, has_alpha: bool, level: int = 9,
               sub_filter: bool = False) -> bytes:
    """RGBA8 버퍼(stride = width*4)를 8비트 PNG로 만든다.

    has_alpha가 False면 color type 2(RGB)로 쓰고 alpha 바이트는 버린다. 타임스탬프·텍스트
    청크를 쓰지 않으므로 같은 입력이면 같은 바이트가 나온다. 행 필터는 직전 행과 같으면
    Up(2), 아니면 None(0)이다(sub_filter=True면 Sub(1)). 같은 행이 반복되는 큰
    그래디언트나 한 행짜리 램프를 작게 담기 위해서다.
    """
    if len(rgba) != width * height * 4:
        raise ValueError("rgba buffer size mismatch")
    bpp = 4 if has_alpha else 3
    stride_in = width * 4
    comp = zlib.compressobj(level)
    parts = []
    zero_row = bytes(width * bpp)
    prev = None
    for y in range(height):
        row = rgba[y * stride_in:(y + 1) * stride_in]
        if not has_alpha:
            # RGBA -> RGB: 4바이트마다 앞 3바이트만 남긴다.
            rgb = bytearray(width * 3)
            rgb[0::3] = row[0::4]
            rgb[1::3] = row[1::4]
            rgb[2::3] = row[2::4]
            row = bytes(rgb)
        if prev is not None and row == prev:
            parts.append(comp.compress(b"\x02" + zero_row))
        elif sub_filter:
            # Sub 필터: 같은 채널의 왼쪽 픽셀과의 차(mod 256).
            diff = bytes(row[:bpp]) + bytes(
                (row[i] - row[i - bpp]) & 0xFF for i in range(bpp, len(row)))
            parts.append(comp.compress(b"\x01" + diff))
        else:
            parts.append(comp.compress(b"\x00" + row))
        prev = row
    parts.append(comp.flush())
    ihdr = struct.pack(">IIBBBBB", width, height, 8, 6 if has_alpha else 2, 0, 0, 0)
    return (
        b"\x89PNG\r\n\x1a\n"
        + _png_chunk(b"IHDR", ihdr)
        + _png_chunk(b"IDAT", b"".join(parts))
        + _png_chunk(b"IEND", b"")
    )


def opaque(rgba: bytes) -> bytes:
    """alpha 채널을 모두 255로 바꾼 사본. RGB PNG를 디코딩했을 때의 RGBA와 같다."""
    out = bytearray(rgba)
    out[3::4] = b"\xff" * (len(rgba) // 4)
    return bytes(out)


# ---------------------------------------------------------------------------
# RIFF / WebP 청크 (RFC 9649 §2.3, §2.4, §2.7)
# ---------------------------------------------------------------------------


def chunk(fourcc: bytes, payload: bytes) -> bytes:
    """청크 하나. RFC 9649 §2.3: 크기는 패딩을 뺀 값, 홀수면 0 패딩 1바이트."""
    if len(fourcc) != 4:
        raise ValueError("fourcc must be 4 bytes")
    pad = b"\x00" if len(payload) & 1 else b""
    return fourcc + struct.pack("<I", len(payload)) + payload + pad


def riff(body_chunks: bytes) -> bytes:
    """'RIFF' + File Size + 'WEBP' + 청크들. RFC 9649 §2.4: File Size = 'WEBP'부터 끝까지."""
    return b"RIFF" + struct.pack("<I", 4 + len(body_chunks)) + b"WEBP" + body_chunks


def parse_chunks(data: bytes) -> list[tuple[bytes, bytes]]:
    """정상 WebP의 최상위 청크 (fourcc, payload) 목록. 변조 시드 해석용이라 엄격하게 검사한다."""
    if data[:4] != b"RIFF" or data[8:12] != b"WEBP":
        raise ValueError("not a RIFF/WEBP file")
    riff_size = struct.unpack("<I", data[4:8])[0]
    if riff_size + 8 != len(data):
        raise ValueError("RIFF size mismatch")
    out = []
    pos = 12
    while pos < len(data):
        fourcc = data[pos:pos + 4]
        size = struct.unpack("<I", data[pos + 4:pos + 8])[0]
        payload = data[pos + 8:pos + 8 + size]
        if len(payload) != size:
            raise ValueError("truncated chunk")
        out.append((fourcc, payload))
        pos += 8 + size + (size & 1)
    return out


def build_webp(chunks: list[tuple[bytes, bytes]]) -> bytes:
    return riff(b"".join(chunk(f, p) for f, p in chunks))


def chunk_offsets(data: bytes) -> list[tuple[bytes, int, int]]:
    """최상위 청크의 (fourcc, 헤더 시작 오프셋, payload 크기). 변조 위치 계산용."""
    out = []
    pos = 12
    while pos + 8 <= len(data):
        fourcc = data[pos:pos + 4]
        size = struct.unpack("<I", data[pos + 4:pos + 8])[0]
        out.append((fourcc, pos, size))
        pos += 8 + size + (size & 1)
    return out


# VP8X 플래그 비트(RFC 9649 §2.7): Rsv(2) I L E X A R(1)
VP8X_ICC = 0x20
VP8X_ALPHA = 0x10
VP8X_EXIF = 0x08
VP8X_XMP = 0x04
VP8X_ANIM = 0x02


def vp8x_payload(flags: int, canvas_w: int, canvas_h: int) -> bytes:
    """VP8X 페이로드 10바이트. 캔버스 폭·높이는 24비트 '값 - 1'(RFC 9649 §2.7)."""
    return (
        bytes([flags & 0xFF, 0, 0, 0])
        + (canvas_w - 1).to_bytes(3, "little")
        + (canvas_h - 1).to_bytes(3, "little")
    )


def anmf_payload(x: int, y: int, w: int, h: int, duration: int, dispose_bg: bool,
                 no_blend: bool, frame_data: bytes) -> bytes:
    """ANMF 페이로드(RFC 9649 §2.7.1.1). Frame X/Y는 실제 오프셋 / 2로 저장한다."""
    flags = (0x02 if no_blend else 0) | (0x01 if dispose_bg else 0)
    return (
        (x // 2).to_bytes(3, "little")
        + (y // 2).to_bytes(3, "little")
        + (w - 1).to_bytes(3, "little")
        + (h - 1).to_bytes(3, "little")
        + duration.to_bytes(3, "little")
        + bytes([flags])
        + frame_data
    )


# ---------------------------------------------------------------------------
# 외부 도구 실행과 기록
# ---------------------------------------------------------------------------


def command_string(args: list[str]) -> str:
    """manifest에 남길 명령 문자열. 경로는 samples/ 기준 상대 경로로 넘겨야 한다."""
    return shlex.join(args)


def run(args: list[str], *, check: bool = True, cwd: Path = SAMPLES_DIR,
        timeout: float | None = 300, preexec_fn=None) -> subprocess.CompletedProcess:
    """도구를 samples/ 디렉터리에서 실행한다. 기록한 명령을 그대로 다시 실행할 수 있게 하기 위해서다."""
    proc = subprocess.run(
        args, cwd=cwd, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
        timeout=timeout, preexec_fn=preexec_fn,
    )
    if check and proc.returncode != 0:
        sys.stderr.write(proc.stderr.decode("utf-8", "replace"))
        raise RuntimeError(f"command failed ({proc.returncode}): {command_string(args)}")
    return proc


def tool_versions(tool: str) -> set[str]:
    """'<tool> -version' 출력에서 libwebp 구성요소 버전들(libsharpyuv 줄 제외).

    cwebp/dwebp/webpmux는 첫 줄에 '1.6.0'만, img2webp/gif2webp/anim_dump 등은
    'WebP Encoder version: 1.6.0' 같은 줄을 쓴다.
    """
    text = run([tool, "-version"], check=False).stdout.decode("ascii", "replace")
    found = set()
    for line in text.splitlines():
        if line.lower().startswith("libsharpyuv"):
            continue
        for token in re.findall(r"\b\d+\.\d+\.\d+\b", line):
            found.add(token)
    return found


def require_libwebp_version() -> None:
    """도구 이미지 밖(예: 호스트 libwebp 1.5.0)에서 커밋 출력을 만드는 실수를 막는다."""
    for tool in ("cwebp", "dwebp", "webpmux", "img2webp", "webpinfo", "anim_dump"):
        versions = tool_versions(tool)
        if versions != {LIBWEBP_VERSION}:
            raise SystemExit(
                f"{tool} reports versions {sorted(versions)}, expected {LIBWEBP_VERSION}. "
                "Run inside the samples tool image (see samples/README.md)."
            )


def generator_info(script: str, tools: list[str]) -> dict:
    """manifest의 generator 필드. libwebp 도구를 썼으면 버전과 도구 이미지를 함께 남긴다."""
    info = {"script": script, "tools": sorted(set(tools))}
    if tools:
        info["libwebp"] = LIBWEBP_VERSION
        info["image"] = "samples/scripts/Dockerfile"
    return info


def dump_json(obj) -> str:
    """결정적 JSON: 키 정렬, 들여쓰기 2, 끝 줄바꿈."""
    return json.dumps(obj, indent=2, sort_keys=True, ensure_ascii=False) + "\n"


def write_text(path: Path, text: str) -> None:
    """UTF-8, LF로 쓴다(Windows에서도 줄바꿈 변환 없음)."""
    ensure_parent(path)
    with open(path, "w", encoding="utf-8", newline="\n") as f:
        f.write(text)


def write_records(name: str, records: list[dict]) -> Path:
    """단계별 중간 기록을 .build/<name>.json으로 쓴다."""
    records = sorted(records, key=lambda r: r["path"])
    paths = [r["path"] for r in records]
    if len(paths) != len(set(paths)):
        raise ValueError(f"duplicate paths in {name}")
    out = BUILD_DIR / f"{name}.json"
    write_text(out, dump_json(records))
    return out


def read_records(name: str) -> list[dict]:
    path = BUILD_DIR / f"{name}.json"
    if not path.exists():
        raise SystemExit(f"missing {rel(path)}; run the previous build step first")
    with open(path, encoding="utf-8") as f:
        return json.load(f)


def clean_dir(path: Path) -> None:
    """생성 디렉터리를 비운다. samples/ 안의 생성 전용 디렉터리만 대상으로 한다."""
    resolved = path.resolve()
    if SAMPLES_DIR not in resolved.parents:
        raise ValueError(f"refusing to clean {resolved}")
    if resolved.exists():
        for p in sorted(resolved.rglob("*"), key=lambda q: len(q.parts), reverse=True):
            if p.is_symlink() or p.is_file():
                p.unlink()
            elif p.is_dir():
                p.rmdir()
    resolved.mkdir(parents=True, exist_ok=True)


def remove_dir(path: Path) -> None:
    """clean_dir로 비운 뒤 디렉터리 자체도 지운다. 단계가 끝난 임시 디렉터리 정리용."""
    clean_dir(path)
    path.resolve().rmdir()
