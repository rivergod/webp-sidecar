#!/usr/bin/env python3
"""손상/악성 입력을 만든다: samples/malformed/ (ADR-0006, testing.md §5).

도구 이미지 안에서 generate.py 다음에 실행한다:
    python3 -I samples/scripts/tamper.py

generated/의 유효 파일을 시드로 삼아 바이트를 바꾸고, libwebp 1.6.0의 실제 반응을 기록한다.
- 관찰 도구: webp_probe features(WebPGetFeatures), webp_probe demux(WebPDemux), webpinfo,
  dwebp(-o 없이 디코딩만), webp_probe anim(WebPAnimDecoder), anim_dump.
- 디코더는 주소 공간 상한(RLIMIT_AS) 1 GiB, 제한 시간 60초로 실행한다. 1 GiB는 서버
  컨테이너 기본 메모리(ADR-0012)와 같다. kind=resource 케이스는 8 GiB / 180초로 한 번 더
  돌려 "자원이 충분하면 libwebp가 받는가"를 따로 기록한다.
- outcome은 파일 종류에 맞는 디코더(정지: dwebp, 애니메이션: WebPAnimDecoder)의 결과다.
결과 기록은 .build/malformed.json에 쓰고 manifest.py가 합친다.
"""

from __future__ import annotations

import json
import struct
import subprocess
import sys
from pathlib import Path

sys.dont_write_bytecode = True
sys.path.insert(0, str(Path(__file__).resolve().parent))

import sampleslib as sl  # noqa: E402

OUT = sl.SAMPLES_DIR / "malformed"
TMP = sl.BUILD_DIR / "tamper-tmp"
GEN = "generated"

# 서버 기본 상한(docs/design/grpc-api.md §3). 어떤 상한에 걸리는지 표시하는 데만 쓴다.
DEFAULT_MAX_PIXELS = 8192 * 8192
DEFAULT_MAX_FRAMES = 1000

LIMIT_DEFAULT = (1 << 30, 60)      # (RLIMIT_AS 바이트, 제한 시간 초)
LIMIT_RESOURCE = (8 << 30, 180)

SEED_LOSSY = f"{GEN}/still/lossy_q75.webp"
SEED_LOSSLESS = f"{GEN}/still/lossless_z9.webp"
SEED_ALPHA = f"{GEN}/still/lossy_alpha_m1.webp"
SEED_META = f"{GEN}/meta/lossy_icc_exif_xmp.webp"
SEED_ANIM = f"{GEN}/anim/loop_0.webp"
SEED_1X1 = f"{GEN}/still/lossless_alpha_1x1.webp"
SEED_PNG = f"{GEN}/inputs/rgb_1x1.png"


def seed(path: str) -> bytes:
    return (sl.SAMPLES_DIR / path).read_bytes()


def fix_riff(data: bytes | bytearray) -> bytes:
    """RIFF File Size를 실제 길이에 맞춘다(크기 필드 변조가 아닌 케이스에서 쓴다)."""
    out = bytearray(data)
    out[4:8] = struct.pack("<I", len(out) - 8)
    return bytes(out)


def find_chunk(data: bytes, fourcc: bytes, nth: int = 0) -> tuple[int, int]:
    """최상위 청크의 (헤더 오프셋, payload 크기)."""
    hits = [(off, size) for f, off, size in sl.chunk_offsets(data) if f == fourcc]
    return hits[nth]


def set_bytes(data: bytes, offset: int, value: bytes) -> bytes:
    out = bytearray(data)
    out[offset:offset + len(value)] = value
    return bytes(out)


def vp8l_bits(data: bytes) -> tuple[int, int]:
    off, _ = find_chunk(data, b"VP8L")
    return off + 8, struct.unpack("<I", data[off + 9:off + 13])[0]


def with_vp8l_bits(data: bytes, transform) -> bytes:
    payload_off, bits = vp8l_bits(data)
    return set_bytes(data, payload_off + 1, struct.pack("<I", transform(bits) & 0xFFFFFFFF))


def vp8l_dims(bits: int, w: int, h: int) -> int:
    """VP8L 헤더(RFC 9649 §3.4): 14비트 폭-1, 14비트 높이-1, alpha 1비트, version 3비트."""
    return (bits & ~0x0FFFFFFF) | (w - 1) | ((h - 1) << 14)


def vp8x_set(data: bytes, *, flags: int | None = None, canvas: tuple[int, int] | None = None,
             raw_canvas: tuple[int, int] | None = None) -> bytes:
    off, _ = find_chunk(data, b"VP8X")
    p = off + 8
    out = bytearray(data)
    if flags is not None:
        out[p] = flags & 0xFF
    if canvas is not None:
        raw_canvas = (canvas[0] - 1, canvas[1] - 1)
    if raw_canvas is not None:
        out[p + 4:p + 7] = raw_canvas[0].to_bytes(3, "little")
        out[p + 7:p + 10] = raw_canvas[1].to_bytes(3, "little")
    return bytes(out)


def rebuild(data: bytes, order: list[tuple[bytes, int]] | None = None,
            replace: dict | None = None, extra: list[tuple[int, tuple[bytes, bytes]]] = ()) -> bytes:
    """청크 목록을 다시 조립한다. order: (fourcc, n번째) 순서, replace: {(fourcc,n): payload}."""
    chunks = sl.parse_chunks(data)
    counts: dict[bytes, int] = {}
    indexed = []
    for f, p in chunks:
        n = counts.get(f, 0)
        counts[f] = n + 1
        indexed.append(((f, n), p))
    table = dict(indexed)
    keys = order if order is not None else [k for k, _ in indexed]
    out = [(k[0], (replace or {}).get(k, table[k])) for k in keys]
    for pos, item in extra:
        out.insert(pos, item)
    return sl.build_webp(out)


def anmf_list(data: bytes) -> list[bytes]:
    return [p for f, p in sl.parse_chunks(data) if f == b"ANMF"]


def vp8l_solid(width: int, height: int, rgba: tuple[int, int, int, int]) -> bytes:
    """단색 VP8L 비트스트림을 직접 만든다. 픽셀당 0비트라 크기와 무관하게 수 바이트다.

    RFC 9649 §3.4 헤더 뒤에 변환 없음(§3.5), color cache 없음(§3.6.2.3), meta prefix 없음
    (§3.7.2.2), 다섯 prefix code(green, red, blue, alpha, distance)를 모두 기호 1개짜리
    simple code(§3.7.2.1.1)로 쓴다. 기호가 하나뿐인 코드는 0비트로 읽히므로 이미지 데이터가 없다.
    """
    acc, nbits = 0, 0

    def put(value: int, n: int) -> None:
        nonlocal acc, nbits
        acc |= (value & ((1 << n) - 1)) << nbits  # VP8L은 LSB부터 채운다
        nbits += n

    r, g, b, a = rgba
    put(0x2F, 8)
    put(width - 1, 14)
    put(height - 1, 14)
    put(1 if a != 255 else 0, 1)
    put(0, 3)  # version 0
    put(0, 1)  # transform_present
    put(0, 1)  # color cache
    put(0, 1)  # meta prefix codes
    for symbol in (g, r, b, a):
        put(1, 1)  # simple code
        put(0, 1)  # num_symbols - 1 = 0
        put(1, 1)  # is_first_8bits
        put(symbol, 8)
    put(1, 1)  # distance: simple code, 1 symbol, 1-bit symbol 0
    put(0, 1)
    put(0, 1)
    put(0, 1)
    return acc.to_bytes((nbits + 7) // 8, "little")


def many_frames(count: int, canvas: tuple[int, int]) -> bytes:
    """1x1 lossless 프레임 count개를 (0,0)에 둔 애니메이션. 프레임은 모두 캔버스 안이다."""
    frame = [p for f, p in sl.parse_chunks(seed(SEED_1X1)) if f == b"VP8L"][0]
    anmf = sl.anmf_payload(0, 0, 1, 1, 10, False, True, sl.chunk(b"VP8L", frame))
    anim = bytes((0, 0, 0, 0)) + struct.pack("<H", 0)
    body = [(b"VP8X", sl.vp8x_payload(sl.VP8X_ANIM | sl.VP8X_ALPHA, *canvas)), (b"ANIM", anim)]
    body += [(b"ANMF", anmf)] * count
    return sl.build_webp(body)


# ---------------------------------------------------------------------------
# 변조 케이스 정의
# ---------------------------------------------------------------------------
# 각 케이스: (파일 이름, 분류, 시드, 바꾼 내용, RFC 절, 디코더 still|anim, kind, 생성 함수)
# kind: corrupt = 형식 오류, resource = 형식은 유효하거나 헤더만 거대한 자원 고갈 입력,
#       spec-edge = RFC가 허용(또는 무시를 요구)하는 변형


def cases() -> list[tuple]:
    lossy, lossless, alpha = seed(SEED_LOSSY), seed(SEED_LOSSLESS), seed(SEED_ALPHA)
    meta, anim = seed(SEED_META), seed(SEED_ANIM)
    vp8_off, vp8_size = find_chunk(lossy, b"VP8 ")
    alph_off, alph_size = find_chunk(alpha, b"ALPH")
    anmf2_off, anmf2_size = find_chunk(anim, b"ANMF", 1)
    exif_off, exif_size = find_chunk(meta, b"EXIF")
    if exif_size % 2 != 1:
        raise SystemExit("seed EXIF payload must have odd size for padding cases")
    vp8_tag = int.from_bytes(lossy[vp8_off + 8:vp8_off + 11], "little")
    frames = anmf_list(anim)
    canvas_w = int.from_bytes(anim[24:27], "little") + 1

    def anmf_field(payload: bytes, index: int, value: int) -> bytes:
        """ANMF 헤더의 3바이트 필드(index 0=X/2, 1=Y/2, 2=W-1, 3=H-1, 4=duration) 교체."""
        out = bytearray(payload)
        out[index * 3:index * 3 + 3] = value.to_bytes(3, "little")
        return bytes(out)

    alph_byte = alpha[alph_off + 8]
    return [
        # --- 잘림 (testing.md §5: 헤더 중간, 청크 중간, 비트스트림 중간) ---
        ("trunc_riff_header_8.webp", "truncation", SEED_LOSSY, "first 8 bytes only ('RIFF' + size)",
         ["§2.4"], "still", "corrupt", lambda: lossy[:8]),
        ("trunc_in_webp_fourcc_11.webp", "truncation", SEED_LOSSY, "first 11 bytes (cut inside 'WEBP')",
         ["§2.4"], "still", "corrupt", lambda: lossy[:11]),
        ("magic_riff_webp_only.webp", "truncation", SEED_LOSSY,
         "'RIFF' + size 4 + 'WEBP' only (12 bytes, consistent RIFF size, no chunks)",
         ["§2.4"], "still", "corrupt", lambda: b"RIFF" + struct.pack("<I", 4) + b"WEBP"),
        ("trunc_chunk_header.webp", "truncation", SEED_LOSSY,
         "cut after first chunk FourCC (16 bytes, chunk size missing)", ["§2.3"], "still", "corrupt",
         lambda: lossy[:16]),
        ("trunc_vp8_half.webp", "truncation", SEED_LOSSY, "lossy file cut at half",
         ["§2.5"], "still", "corrupt", lambda: lossy[:len(lossy) // 2]),
        ("trunc_vp8_last_byte.webp", "truncation", SEED_LOSSY, "lossy file without its last byte",
         ["§2.5"], "still", "corrupt", lambda: lossy[:-1]),
        ("trunc_vp8l_half.webp", "truncation", SEED_LOSSLESS, "lossless file cut at half",
         ["§2.6"], "still", "corrupt", lambda: lossless[:len(lossless) // 2]),
        ("trunc_alph_mid.webp", "truncation", SEED_ALPHA, "cut in the middle of the ALPH payload",
         ["§2.7.1.2"], "still", "corrupt", lambda: alpha[:alph_off + 8 + alph_size // 2]),
        ("trunc_anim_mid_frame.webp", "truncation", SEED_ANIM,
         "cut in the middle of the 2nd ANMF chunk", ["§2.7.1.1"], "anim", "corrupt",
         lambda: anim[:anmf2_off + 8 + anmf2_size // 2]),
        ("vp8x_only.webp", "truncation", SEED_ALPHA, "VP8X chunk only (no image chunk), RIFF size fixed",
         ["§2.7"], "still", "corrupt", lambda: rebuild(alpha, order=[(b"VP8X", 0)])),

        # --- RIFF File Size ---
        ("riff_size_huge.webp", "riff-size", SEED_LOSSY, "RIFF size = 0xFFFFFFF0",
         ["§2.4"], "still", "corrupt", lambda: set_bytes(lossy, 4, struct.pack("<I", 0xFFFFFFF0))),
        ("riff_size_small.webp", "riff-size", SEED_LOSSY, "RIFF size = 20 (smaller than the file)",
         ["§2.4"], "still", "corrupt", lambda: set_bytes(lossy, 4, struct.pack("<I", 20))),
        ("riff_size_zero.webp", "riff-size", SEED_LOSSY, "RIFF size = 0",
         ["§2.4"], "still", "corrupt", lambda: set_bytes(lossy, 4, struct.pack("<I", 0))),
        ("riff_size_odd.webp", "riff-size", SEED_LOSSY, "RIFF size = actual + 1 (odd, 1 byte missing)",
         ["§2.4"], "still", "corrupt",
         lambda: set_bytes(lossy, 4, struct.pack("<I", len(lossy) - 8 + 1))),
        ("riff_trailing_data.webp", "riff-size", SEED_LOSSY,
         "16 bytes of 0xAA appended after the RIFF payload (RIFF size unchanged)",
         ["§2.4"], "still", "spec-edge", lambda: lossy + b"\xaa" * 16),

        # --- Chunk Size ---
        ("chunk_size_ffffffff.webp", "chunk-size", SEED_LOSSY, "VP8 chunk size = 0xFFFFFFFF",
         ["§2.3"], "still", "corrupt", lambda: set_bytes(lossy, vp8_off + 4, b"\xff\xff\xff\xff")),
        ("chunk_size_beyond_riff.webp", "chunk-size", SEED_LOSSY,
         "VP8 chunk size = actual + 100 (past end of file)", ["§2.3"], "still", "corrupt",
         lambda: set_bytes(lossy, vp8_off + 4, struct.pack("<I", vp8_size + 100))),
        ("chunk_size_short.webp", "chunk-size", SEED_LOSSY,
         "VP8 chunk size = actual - 100 (rest of the bitstream left after the chunk)",
         ["§2.3"], "still", "corrupt",
         lambda: set_bytes(lossy, vp8_off + 4, struct.pack("<I", vp8_size - 100))),
        ("vp8x_chunk_size_9.webp", "chunk-size", SEED_ALPHA, "VP8X chunk size = 9 (must be 10)",
         ["§2.7"], "still", "corrupt", lambda: set_bytes(alpha, 16, struct.pack("<I", 9))),
        ("padding_nonzero.webp", "chunk-size", SEED_META,
         "padding byte after odd-sized EXIF chunk set to 0xFF (RFC: MUST be 0)",
         ["§2.3"], "still", "spec-edge",
         lambda: set_bytes(meta, exif_off + 8 + exif_size, b"\xff")),
        ("padding_missing.webp", "chunk-size", SEED_META,
         "padding byte after odd-sized EXIF chunk removed, RIFF size fixed",
         ["§2.3"], "still", "corrupt",
         lambda: fix_riff(meta[:exif_off + 8 + exif_size] + meta[exif_off + 8 + exif_size + 1:])),
        ("anmf_size_short.webp", "chunk-size", SEED_ANIM, "2nd ANMF chunk size reduced by 4",
         ["§2.7.1.1"], "anim", "corrupt",
         lambda: set_bytes(anim, anmf2_off + 4, struct.pack("<I", anmf2_size - 4))),

        # --- FourCC / 매직 ---
        ("fourcc_webq.webp", "fourcc", SEED_LOSSY, "form type 'WEBP' -> 'WEBQ'",
         ["§2.4"], "still", "corrupt", lambda: set_bytes(lossy, 8, b"WEBQ")),
        ("fourcc_rifx.webp", "fourcc", SEED_LOSSY, "'RIFF' -> 'RIFX'",
         ["§2.4"], "still", "corrupt", lambda: set_bytes(lossy, 0, b"RIFX")),
        ("fourcc_vp8_lowercase.webp", "fourcc", SEED_LOSSY,
         "'VP8 ' -> 'vp8 ' (FourCC is case-sensitive; no image chunk remains)",
         ["§2.2", "§2.7.1.3"], "still", "corrupt", lambda: set_bytes(lossy, vp8_off, b"vp8 ")),
        ("empty.webp", "not-webp", SEED_LOSSY, "0-byte file", ["§2.4"], "still", "corrupt",
         lambda: b""),
        ("not_webp_png.webp", "not-webp", SEED_PNG, "PNG file with .webp name",
         ["§2.4"], "still", "corrupt", lambda: seed(SEED_PNG)),
        ("riff_avi.webp", "not-webp", SEED_LOSSY, "RIFF with form type 'AVI ' and a LIST chunk",
         ["§2.4"], "still", "corrupt",
         lambda: b"RIFF" + struct.pack("<I", 4 + 12) + b"AVI " + sl.chunk(b"LIST", b"\x00" * 4)),

        # --- VP8X 필드 ---
        ("vp8x_canvas_2pow24.webp", "vp8x", SEED_ALPHA,
         "VP8X canvas fields = 0xFFFFFF (16777216 x 16777216, area > 2^32-1)",
         ["§2.7"], "still", "corrupt", lambda: vp8x_set(alpha, raw_canvas=(0xFFFFFF, 0xFFFFFF))),
        ("vp8x_canvas_65536x65536.webp", "vp8x", SEED_ALPHA,
         "VP8X canvas 65536 x 65536 (area 2^32 > 2^32-1)",
         ["§2.7"], "still", "corrupt", lambda: vp8x_set(alpha, canvas=(65536, 65536))),
        ("vp8x_canvas_1x1_mismatch.webp", "vp8x", SEED_ALPHA,
         "VP8X canvas 1x1, bitstream 64x48", ["§2.7", "§2.5"], "still", "corrupt",
         lambda: vp8x_set(alpha, canvas=(1, 1))),
        ("vp8x_reserved_flag_bits.webp", "vp8x", SEED_ALPHA,
         "VP8X flag byte reserved bits set (flags |= 0xC1; RFC: MUST be 0, readers MUST ignore)",
         ["§2.7"], "still", "spec-edge", lambda: vp8x_set(alpha, flags=alpha[20] | 0xC1)),
        ("vp8x_reserved_24bits.webp", "vp8x", SEED_ALPHA,
         "VP8X 24-bit reserved field = 0xFFFFFF (RFC: MUST be 0, readers MUST ignore)",
         ["§2.7"], "still", "spec-edge", lambda: set_bytes(alpha, 21, b"\xff\xff\xff")),
        ("vp8x_anim_flag_on_still.webp", "vp8x", SEED_ALPHA,
         "VP8X animation flag set on a still image (no ANIM/ANMF)",
         ["§2.7", "§2.7.1.1"], "still", "corrupt",
         lambda: vp8x_set(alpha, flags=alpha[20] | sl.VP8X_ANIM)),
        ("vp8x_alpha_flag_cleared.webp", "vp8x", SEED_ALPHA,
         "VP8X alpha flag cleared while ALPH chunk is present",
         ["§2.7", "§2.7.1.2"], "still", "spec-edge",
         lambda: vp8x_set(alpha, flags=alpha[20] & ~sl.VP8X_ALPHA)),
        ("vp8x_icc_flag_without_iccp.webp", "vp8x", SEED_META,
         "ICCP chunk removed but VP8X ICC flag kept",
         ["§2.7", "§2.7.1.4"], "still", "spec-edge",
         lambda: rebuild(meta, order=[(b"VP8X", 0), (b"VP8 ", 0), (b"EXIF", 0), (b"XMP ", 0)])),

        # --- VP8L 헤더 ---
        ("vp8l_bad_signature.webp", "vp8l", SEED_LOSSLESS, "VP8L signature 0x2F -> 0x2E",
         ["§3.4"], "still", "corrupt",
         lambda: set_bytes(lossless, find_chunk(lossless, b"VP8L")[0] + 8, b"\x2e")),
        ("vp8l_version_7.webp", "vp8l", SEED_LOSSLESS, "VP8L version bits = 7 (MUST be 0)",
         ["§3.4"], "still", "corrupt", lambda: with_vp8l_bits(lossless, lambda b: b | (7 << 29))),
        ("vp8l_width_plus_one.webp", "vp8l", SEED_LOSSLESS,
         "VP8L header width 64 -> 65 (bitstream encodes 64x48)", ["§3.4"], "still", "corrupt",
         lambda: with_vp8l_bits(lossless, lambda b: vp8l_dims(b, 65, 48))),
        ("vp8l_header_16383x16383.webp", "huge-dimensions", SEED_LOSSLESS,
         "VP8L chunk = header declaring 16383x16383 (alpha) + 16 zero bytes, no image data",
         ["§3.4"], "still", "resource",
         lambda: sl.build_webp([(b"VP8L", bytes([0x2F]) + struct.pack(
             "<I", (16383 - 1) | ((16383 - 1) << 14) | (1 << 28)) + b"\x00" * 16)])),
        ("vp8l_bomb_16383x16383.webp", "huge-dimensions", "scripts/tamper.py:vp8l_solid",
         "hand-built solid-color VP8L bitstream (no transforms, five 1-symbol prefix codes, "
         "0 bits per pixel) declaring 16383x16383: a valid 32-byte file that decodes to 1 GiB RGBA",
         ["§3.4", "§3.7.2.1.1", "§4"], "still", "resource",
         lambda: sl.build_webp([(b"VP8L", vp8l_solid(16383, 16383, (0x20, 0x80, 0xE0, 0xFF)))])),

        # --- VP8 프레임 헤더 (RFC 6386 §9.1) ---
        ("vp8_bad_start_code.webp", "vp8", SEED_LOSSY, "VP8 start code 9d 01 2a -> 9d 01 2b",
         ["§2.5"], "still", "corrupt", lambda: set_bytes(lossy, vp8_off + 8 + 5, b"\x2b")),
        ("vp8_partition_size_overflow.webp", "vp8", SEED_LOSSY,
         "VP8 first partition size = 0x7FFFF (larger than the chunk)", ["§2.5"], "still", "corrupt",
         lambda: set_bytes(lossy, vp8_off + 8,
                           ((vp8_tag & 0x1F) | (0x7FFFF << 5)).to_bytes(3, "little"))),
        ("vp8_not_keyframe.webp", "vp8", SEED_LOSSY, "VP8 frame tag marks an interframe",
         ["§2.5"], "still", "corrupt",
         lambda: set_bytes(lossy, vp8_off + 8, (vp8_tag | 1).to_bytes(3, "little"))),
        ("vp8_header_16383x16383.webp", "huge-dimensions", SEED_LOSSY,
         "VP8 frame header width/height = 16383 (data encodes 64x48)", ["§2.5"], "still", "resource",
         lambda: set_bytes(lossy, vp8_off + 8 + 6, struct.pack("<HH", 16383, 16383))),

        # --- ALPH ---
        ("alph_reserved_bits.webp", "alph", SEED_ALPHA,
         "ALPH header reserved bits = 3 (RFC: readers MUST ignore)", ["§2.7.1.2"], "still",
         "spec-edge", lambda: set_bytes(alpha, alph_off + 8, bytes([alph_byte | 0xC0]))),
        ("alph_bad_compression.webp", "alph", SEED_ALPHA,
         "ALPH compression method = 2 (only 0/1 defined)", ["§2.7.1.2"], "still", "corrupt",
         lambda: set_bytes(alpha, alph_off + 8, bytes([(alph_byte & ~0x03) | 0x02]))),
        ("alph_after_vp8.webp", "chunk-order", SEED_ALPHA, "ALPH chunk placed after VP8",
         ["§2.7"], "still", "corrupt",
         lambda: rebuild(alpha, order=[(b"VP8X", 0), (b"VP8 ", 0), (b"ALPH", 0)])),

        # --- 청크 순서 / 중복 ---
        ("iccp_after_image.webp", "chunk-order", SEED_META, "ICCP chunk placed after VP8",
         ["§2.7", "§2.7.1.4"], "still", "corrupt",
         lambda: rebuild(meta, order=[(b"VP8X", 0), (b"VP8 ", 0), (b"ICCP", 0), (b"EXIF", 0),
                                      (b"XMP ", 0)])),
        ("duplicate_vp8.webp", "chunk-order", SEED_META, "VP8 chunk repeated twice",
         ["§2.7"], "still", "corrupt",
         lambda: rebuild(meta, order=[(b"VP8X", 0), (b"ICCP", 0), (b"VP8 ", 0), (b"VP8 ", 0),
                                      (b"EXIF", 0), (b"XMP ", 0)])),

        # --- 애니메이션 ---
        ("anim_canvas_16384x16384.webp", "huge-dimensions", SEED_ANIM,
         "valid animation with VP8X canvas patched to 16384x16384 (frames stay inside)",
         ["§2.7", "§2.7.2"], "anim", "resource", lambda: vp8x_set(anim, canvas=(16384, 16384))),
        ("anim_frame_outside_canvas.webp", "anim", SEED_ANIM,
         f"2nd frame X offset = {canvas_w - 8} so that x + width > canvas width ({canvas_w})",
         ["§2.7.1.1", "§2.7.2"], "anim", "corrupt",
         lambda: rebuild(anim, replace={(b"ANMF", 1): anmf_field(frames[1], 0, (canvas_w - 8) // 2)})),
        ("anim_frame_offset_max.webp", "anim", SEED_ANIM, "2nd frame X field = 0xFFFFFF",
         ["§2.7.1.1", "§2.7.2"], "anim", "corrupt",
         lambda: rebuild(anim, replace={(b"ANMF", 1): anmf_field(frames[1], 0, 0xFFFFFF)})),
        ("anim_frame_size_mismatch.webp", "anim", SEED_ANIM,
         "2nd ANMF declares 24x24 while its bitstream is 16x16",
         ["§2.7.1.1"], "anim", "corrupt",
         lambda: rebuild(anim, replace={(b"ANMF", 1): anmf_field(anmf_field(frames[1], 2, 23), 3, 23)})),
        ("anim_zero_frames.webp", "anim", SEED_ANIM, "VP8X(A) + ANIM, all ANMF removed",
         ["§2.7.1.1"], "anim", "corrupt", lambda: rebuild(anim, order=[(b"VP8X", 0), (b"ANIM", 0)])),
        ("anim_missing_anim_chunk.webp", "anim", SEED_ANIM, "ANIM chunk removed (A flag kept)",
         ["§2.7.1.1"], "anim", "corrupt",
         lambda: rebuild(anim, order=[(b"VP8X", 0), (b"ANMF", 0), (b"ANMF", 1)])),
        ("anim_flag_cleared.webp", "anim", SEED_ANIM, "VP8X animation flag cleared (ANIM/ANMF kept)",
         ["§2.7", "§2.7.1.1"], "anim", "corrupt",
         lambda: vp8x_set(anim, flags=anim[20] & ~sl.VP8X_ANIM)),
        ("anim_nested_anmf.webp", "anim", SEED_ANIM, "2nd ANMF frame data wrapped in another ANMF",
         ["§2.7.1.1"], "anim", "corrupt",
         lambda: rebuild(anim, replace={(b"ANMF", 1): frames[1][:16] + sl.chunk(b"ANMF", frames[1])})),
        ("anim_frames_1001.webp", "frame-count", SEED_1X1,
         "1001 frames of 1x1 on a 1x1 canvas (server default max_frames = 1000)",
         ["§2.7.1.1"], "anim", "resource", lambda: many_frames(1001, (1, 1))),
        ("anim_frames_1000_canvas_4096.webp", "frame-count", SEED_1X1,
         "1000 frames of 1x1 on a 4096x4096 canvas: within default limits, but every frame "
         "composites a 64 MiB canvas (about 64 GiB of output)",
         ["§2.7.2", "§4"], "anim", "resource", lambda: many_frames(1000, (4096, 4096))),
    ]


# ---------------------------------------------------------------------------
# 관찰
# ---------------------------------------------------------------------------


def run_limited(args: list[str], limit: tuple[int, int]) -> tuple[int | str, str, str]:
    """RLIMIT_AS와 제한 시간을 걸고 실행한다. (종료 코드 또는 'timeout', stdout, stderr)."""
    import resource  # 도구 이미지(Linux) 전용

    as_bytes, seconds = limit

    def preexec() -> None:
        resource.setrlimit(resource.RLIMIT_AS, (as_bytes, as_bytes))

    try:
        proc = sl.run(args, check=False, timeout=seconds, preexec_fn=preexec)
    except subprocess.TimeoutExpired:
        return "timeout", "", ""
    return (proc.returncode, proc.stdout.decode("utf-8", "replace"),
            proc.stderr.decode("utf-8", "replace"))


def clean_message(text: str, rel_path: str) -> str:
    """도구 메시지에서 경로와 시간 정보를 지워 결정적으로 만든다."""
    lines = []
    text = text.replace(rel_path, "<file>").replace(sl.rel(TMP), "<tmp>")
    for line in text.splitlines():
        line = line.strip()
        if not line or line.startswith("Time to decode") or "Nothing written" in line:
            continue
        lines.append(line)
    return " | ".join(lines)[:300]


def observe(rel_path: str, limit: tuple[int, int], with_anim_dump: bool = True) -> dict:
    """libwebp 1.6.0 도구들의 반응을 모은다.

    with_anim_dump=False면 anim_dump를 건너뛴다. 큰 상한에서는 anim_dump가 GiB 단위 PAM을
    디스크에 쓰기 때문이다(디코더 판정에는 webp_probe anim을 쓴다).
    """
    obs = {}
    rc, out, _ = run_limited(["webp_probe", "features", rel_path], limit)
    obs["features"] = json.loads(out) if out.strip() else {"ok": False, "exit": rc}
    if "format" in obs["features"]:
        obs["features"]["format"] = {0: "mixed", 1: "lossy", 2: "lossless"}[obs["features"]["format"]]
    rc, out, _ = run_limited(["webp_probe", "demux", rel_path], limit)
    demux = json.loads(out) if out.strip() else {"ok": False, "exit": rc}
    if demux.get("ok"):
        demux = {k: demux[k] for k in ("ok", "canvas_width", "canvas_height", "frame_count")}
    obs["demux"] = demux
    rc, out, err = run_limited(["webpinfo", "-quiet", rel_path], limit)
    obs["webpinfo"] = {"result": "ok" if rc == 0 else "error", "message": clean_message(out + err, rel_path)}
    rc, out, err = run_limited(["dwebp", rel_path], limit)
    obs["dwebp"] = {"result": "accept" if rc == 0 else "reject", "exit": rc,
                    "message": clean_message(err, rel_path)}
    rc, out, _ = run_limited(["webp_probe", "anim", rel_path, "-"], limit)
    anim = json.loads(out) if out.strip() else {"ok": False, "exit": rc}
    obs["anim_decoder"] = {"result": "accept" if anim.get("ok") else "reject",
                           **({"stage": anim["stage"]} if "stage" in anim else {}),
                           **({"exit": rc} if not out.strip() else {}),
                           **({"decoded_frames": anim["decoded_frames"]} if "decoded_frames" in anim else {})}
    if with_anim_dump:
        dump_dir = TMP / "anim_dump"
        sl.clean_dir(dump_dir)
        rc, out, err = run_limited(["anim_dump", "-folder", sl.rel(dump_dir), "-pam", rel_path],
                                   limit)
        obs["anim_dump"] = {"result": "accept" if rc == 0 else "reject", "exit": rc,
                            "message": clean_message(out + err, rel_path)}
        sl.clean_dir(dump_dir)
    return obs


def accepted_pixels(rel_path: str, decoder: str) -> dict:
    """작은 파일을 libwebp가 받았을 때의 디코딩 결과 해시(테스트가 '같은 결과'를 확인하도록)."""
    raw = TMP / "out.rgba"
    if decoder == "still":
        rc, out, _ = run_limited(["webp_probe", "still", rel_path, sl.rel(raw)], LIMIT_DEFAULT)
        info = json.loads(out)
        return {"width": info["width"], "height": info["height"],
                "rgba_sha256": sl.sha256_file(raw)}
    rc, out, _ = run_limited(["webp_probe", "anim", rel_path, sl.rel(raw)], LIMIT_DEFAULT)
    info = json.loads(out)
    data = raw.read_bytes()
    n = info["canvas_width"] * info["canvas_height"] * 4
    return {"canvas_width": info["canvas_width"], "canvas_height": info["canvas_height"],
            "frame_rgba_sha256": [sl.sha256_bytes(data[i:i + n]) for i in range(0, len(data), n)]}


def exceeded_limits(obs: dict) -> list[str]:
    """선언된 크기·프레임 수가 서버 기본 상한(grpc-api.md §3)을 넘는지."""
    out = set()
    f, d = obs["features"], obs["demux"]
    if f.get("ok") and f["width"] * f["height"] > DEFAULT_MAX_PIXELS:
        out.add("max_pixels")
    if d.get("ok"):
        if d["canvas_width"] * d["canvas_height"] > DEFAULT_MAX_PIXELS:
            out.add("max_pixels")
        if d["frame_count"] > DEFAULT_MAX_FRAMES:
            out.add("max_frames")
    return sorted(out)


def main() -> None:
    sl.require_libwebp_version()
    sl.clean_dir(OUT)
    sl.clean_dir(TMP)
    records = []
    seen = set()
    for name, category, seed_path, change, rfc, decoder, kind, build in cases():
        if name in seen:
            raise SystemExit(f"duplicate case {name}")
        seen.add(name)
        path = OUT / name
        sl.write_bytes(path, build())
        rel_path = sl.rel(path)
        obs = observe(rel_path, LIMIT_DEFAULT)
        primary = obs["dwebp"] if decoder == "still" else obs["anim_decoder"]
        expected = {
            "reference": f"libwebp {sl.LIBWEBP_VERSION}",
            "kind": kind,
            "decoder": decoder,
            "outcome": primary["result"],
            "observed": obs,
            "exceeds_default_limits": exceeded_limits(obs),
        }
        if kind == "resource":
            big = observe(rel_path, LIMIT_RESOURCE, with_anim_dump=False)
            p2 = big["dwebp"] if decoder == "still" else big["anim_decoder"]
            expected["outcome_with_8gib"] = p2["result"] if p2.get("exit") != "timeout" else "timeout"
        elif primary["result"] == "accept":
            expected["decode"] = accepted_pixels(rel_path, decoder)
        records.append({
            "path": rel_path,
            "origin": {"type": "tampered", "script": "scripts/tamper.py", "seed": seed_path,
                       "change": change},
            "license": sl.SELF_LICENSE,
            "tags": sorted({"malformed", category, kind}),
            "rfc": sorted(set(rfc)),
            "generator": sl.generator_info("scripts/tamper.py", []),
            "expected": expected,
        })
        print(f"{name:40s} {kind:9s} {decoder:5s} -> {primary['result']}")
    sl.remove_dir(TMP)
    out = sl.write_records("malformed", records)
    print(f"tamper: {len(records)} records -> {sl.rel(out)}")


if __name__ == "__main__":
    main()
