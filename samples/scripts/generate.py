#!/usr/bin/env python3
"""자체 생성 샘플을 만든다: samples/generated/{inputs,still,anim,meta}/ (ADR-0006).

도구 이미지(samples/scripts/Dockerfile, libwebp 1.6.0) 안에서 실행한다:
    python3 -I samples/scripts/generate.py

- 입력(PNG, EXIF, XMP)은 표준 라이브러리로 결정적으로 합성한다. ICC는 third_party/의
  CC0 프로파일(Compact-ICC-Profiles)을 쓴다.
- WebP는 cwebp / webpmux / img2webp로 만들고, 도구로 만들 수 없는 청크 배치는 RIFF를
  직접 조립한다. 실행한 명령은 samples/ 기준 상대 경로로 기록한다.
- 결과 기록은 .build/generated.json에 쓰고 manifest.py가 manifest.json에 합친다.
"""

from __future__ import annotations

import struct
import sys
from pathlib import Path

sys.dont_write_bytecode = True
sys.path.insert(0, str(Path(__file__).resolve().parent))

import sampleslib as sl  # noqa: E402

GEN = sl.SAMPLES_DIR / "generated"
INPUTS = GEN / "inputs"
FRAMES = sl.BUILD_DIR / "frames"  # 애니메이션 프레임용 중간 WebP(커밋하지 않음)
TMP = sl.BUILD_DIR / "gen-tmp"

# third_party ICC 프로파일 (CC0-1.0). manifest.py가 이 기록으로 출처를 남긴다.
COMPACT_ICC = {
    "repo": "https://github.com/saucecontrol/Compact-ICC-Profiles",
    "commit": "bdd84663061bc4ae95ca70decff54f581e27f702",
    "files": {
        "third_party/compact-icc/sRGB-v4.icc": (
            "profiles/sRGB-v4.icc",
            "c56e1685d888f5edb92fe07f2750f387f8fe8e91b32ff8fb0b56bfbbb9458353",
        ),
        "third_party/compact-icc/DisplayP3-v4.icc": (
            "profiles/DisplayP3-v4.icc",
            "cb51de38e482ee974c0c76b9689e16aad04bad16e226fed2f30c842d15ff3a3d",
        ),
        "third_party/compact-icc/LICENSE": (
            "license",
            "469d87b5fa48f8aaf69e25f4fcac3a309c97dbb7d561b917638abdb640e004d9",
        ),
    },
}
ICC_SRGB = "third_party/compact-icc/sRGB-v4.icc"
ICC_P3 = "third_party/compact-icc/DisplayP3-v4.icc"

records: list[dict] = []
_pending_cmds: list[str] = []


# ---------------------------------------------------------------------------
# 기록
# ---------------------------------------------------------------------------


def record(path: Path, *, tags: list[str], rfc: list[str], tools: list[str], commands=None,
           script: str | None = None, notes: str | None = None, source: str | None = None,
           source_rgba: dict | None = None) -> None:
    """생성물 하나의 출처 기록. sha256/size/expected는 뒤 단계에서 채운다."""
    origin = {"type": "generated", "script": "scripts/generate.py"}
    if commands:
        origin["commands"] = list(commands)
    if script:
        origin["step"] = script
    entry = {
        "path": sl.rel(path),
        "origin": origin,
        "license": sl.SELF_LICENSE,
        "tags": sorted(set(tags)),
        "rfc": sorted(set(rfc)),
        "generator": sl.generator_info("scripts/generate.py", tools),
    }
    if notes:
        entry["notes"] = notes
    if source:
        entry["source"] = source
    if source_rgba:
        entry["source_rgba"] = source_rgba
    records.append(entry)


def tool(args: list[str]) -> str:
    """도구를 실행하고 명령 문자열을 돌려준다."""
    sl.run(args)
    return sl.command_string(args)


# ---------------------------------------------------------------------------
# 입력 이미지 합성
# ---------------------------------------------------------------------------

# 생성한 PNG의 RGBA(디코딩했을 때의 값) 해시. lossless -exact 결과 비교에 쓴다.
png_rgba: dict[str, dict] = {}


def pattern_rgba(w: int, h: int, alpha: str) -> bytes:
    """작은 테스트 패턴: 2축 그래디언트 + XOR 무늬 + 원 하나.

    alpha="opaque": 모두 255.
    alpha="radial": 가운데 255, 가장자리로 갈수록 0. 모서리는 alpha=0이지만 RGB는 0이 아니다
    (cwebp -exact 유무에 따라 결과가 달라지는지 보기 위해서다).
    """
    buf = bytearray(w * h * 4)
    # 타원 경계 T: (2x-(w-1))^2*h^2 + (2y-(h-1))^2*w^2 = T 일 때 가장자리.
    t = (w * h) ** 2
    cx, cy, cr = (w * 5) // 8, h // 3, max(1, min(w, h) // 5)
    for y in range(h):
        for x in range(w):
            r = (x * 255) // max(1, w - 1)
            g = (y * 255) // max(1, h - 1)
            b = ((x ^ y) * 8) & 0xFF
            if (x - cx) ** 2 + (y - cy) ** 2 <= cr * cr:
                r, g, b = 240, 240, 32
            if alpha == "opaque":
                a = 255
            else:
                q = (2 * x - (w - 1)) ** 2 * h * h + (2 * y - (h - 1)) ** 2 * w * w
                a = max(0, min(255, (255 * 2 * (t - q)) // t))
            i = (y * w + x) * 4
            buf[i:i + 4] = bytes((r, g, b, a))
    return bytes(buf)


def solid_rgba(w: int, h: int, rgba: tuple[int, int, int, int]) -> bytes:
    return bytes(rgba) * (w * h)


def write_png(name: str, w: int, h: int, rgba: bytes, has_alpha: bool, *, tags: list[str],
              notes: str, sub_filter: bool = False) -> str:
    """PNG 입력을 쓰고 기록한다. RGB PNG의 기대 RGBA는 alpha=255로 채운 값이다."""
    path = INPUTS / name
    sl.write_bytes(path, sl.encode_png(w, h, rgba, has_alpha, sub_filter=sub_filter))
    decoded = rgba if has_alpha else sl.opaque(rgba)
    info = {"width": w, "height": h, "rgba_sha256": sl.sha256_bytes(decoded)}
    png_rgba[sl.rel(path)] = info
    record(path, tags=["input", "png"] + tags, rfc=[], tools=[], script="write_png",
           notes=notes, source_rgba=info)
    return sl.rel(path)


def make_inputs() -> dict[str, str]:
    """정지·애니메이션용 PNG 입력. 반환: 이름 -> samples/ 기준 경로."""
    p = {}
    p["rgb_64x48"] = write_png("rgb_64x48.png", 64, 48, pattern_rgba(64, 48, "opaque"), False,
                               tags=["opaque"], notes="기본 불투명 패턴")
    p["rgba_64x48"] = write_png("rgba_64x48.png", 64, 48, pattern_rgba(64, 48, "radial"), True,
                                tags=["alpha"],
                                notes="방사형 alpha. 모서리는 alpha=0이고 RGB는 0이 아니다")
    p["rgb_1x1"] = write_png("rgb_1x1.png", 1, 1, bytes((0x12, 0x34, 0x56, 0xFF)), False,
                             tags=["opaque", "tiny"], notes="1x1")
    p["rgba_1x1"] = write_png("rgba_1x1.png", 1, 1, bytes((0x12, 0x34, 0x56, 0x78)), True,
                              tags=["alpha", "tiny"], notes="1x1, RGBA=(0x12,0x34,0x56,0x78)")
    p["rgb_1x13"] = write_png("rgb_1x13.png", 1, 13, pattern_rgba(1, 13, "opaque"), False,
                              tags=["opaque", "tiny"], notes="폭 1")
    p["rgb_13x1"] = write_png("rgb_13x1.png", 13, 1, pattern_rgba(13, 1, "opaque"), False,
                              tags=["opaque", "tiny"], notes="높이 1")
    p["rgb_17x9"] = write_png("rgb_17x9.png", 17, 9, pattern_rgba(17, 9, "opaque"), False,
                              tags=["opaque", "odd-size"], notes="홀수 크기(chroma 서브샘플링 경계)")
    p["rgba_17x9"] = write_png("rgba_17x9.png", 17, 9, pattern_rgba(17, 9, "radial"), True,
                               tags=["alpha", "odd-size"], notes="홀수 크기 + alpha")

    # 3840x2160: 모든 행이 같은 가로 그래디언트. 파일은 작지만 디코딩 결과(RGBA)는
    # 33,177,600 B로 gRPC 기본 메시지 한도(4 MiB)를 넘는다(ADR-0003 청크 스트리밍 검증용).
    w, h = 3840, 2160
    row = bytearray(w * 4)
    row[0::4] = bytes((x * 255) // (w - 1) for x in range(w))
    row[1::4] = bytes(255 - (x * 255) // (w - 1) for x in range(w))
    row[2::4] = b"\x40" * w
    row[3::4] = b"\xff" * w
    p["gradient_3840x2160"] = write_png(
        "gradient_3840x2160.png", w, h, bytes(row) * h, False, tags=["opaque", "large"],
        notes="모든 행이 같은 가로 그래디언트. RGBA 33,177,600 B")

    # VP8 최대 폭(16383, RFC 6386 §9.1 / WEBP_MAX_DIMENSION).
    w = 16383
    row = bytearray(w * 4)
    row[0::4] = bytes(x & 0xFF for x in range(w))
    row[1::4] = bytes((x >> 6) & 0xFF for x in range(w))
    row[2::4] = b"\x80" * w
    row[3::4] = b"\xff" * w
    p["ramp_16383x1"] = write_png("ramp_16383x1.png", w, 1, bytes(row), False,
                                  tags=["opaque", "edge"], notes="폭 16383(VP8/인코더 최대)",
                                  sub_filter=True)
    p["solid_16383x1"] = write_png(
        "solid_16383x1.png", 16383, 1, solid_rgba(16383, 1, (0x20, 0x80, 0xE0, 0xFF)), False,
        tags=["opaque", "edge"], notes="단색 16383x1. VP8L 헤더 폭 패치(16384x1) 시드")

    # 애니메이션 프레임. lossless -exact로 인코딩하면 합성 결과를 손으로 계산할 수 있다.
    w = h = 48
    bg = bytearray(w * h * 4)
    for y in range(h):
        for x in range(w):
            i = (y * w + x) * 4
            bg[i:i + 4] = bytes((32 + x * 2, 64 + y * 2, 192, 255))
    p["anim_bg"] = write_png("anim_bg_48x48.png", w, h, bytes(bg), False,
                             tags=["opaque", "anim-frame"], notes="애니메이션 배경 프레임")
    w = h = 24
    ov = bytearray(w * h * 4)
    for y in range(h):
        for x in range(w):
            i = (y * w + x) * 4
            if 8 <= x < 16 and 8 <= y < 16:
                ov[i:i + 4] = bytes((0, 255, 255, 0))  # alpha=0, RGB는 0이 아님
            else:
                ov[i:i + 4] = bytes((255, 0, 0, 128))
    p["anim_overlay"] = write_png(
        "anim_overlay_24x24.png", w, h, bytes(ov), True, tags=["alpha", "anim-frame"],
        notes="반투명 빨강(alpha 128), 가운데 8x8은 alpha=0 + RGB (0,255,255)")
    w = h = 16
    sm = bytearray(w * h * 4)
    for y in range(h):
        for x in range(w):
            i = (y * w + x) * 4
            edge = x in (0, w - 1) or y in (0, h - 1)
            sm[i:i + 4] = bytes((255, 255, 0, 255) if edge else (0, 200, 0, 255))
    p["anim_small"] = write_png("anim_small_16x16.png", w, h, bytes(sm), False,
                                tags=["opaque", "anim-frame"], notes="초록 + 노랑 테두리")
    # img2webp용 같은 크기 연속 프레임: 배경 위에서 흰 사각형이 움직인다.
    for k in range(3):
        fr = bytearray(bg)
        for y in range(8 + 6 * k, 20 + 6 * k):
            for x in range(4 + 12 * k, 16 + 12 * k):
                i = (y * 48 + x) * 4
                fr[i:i + 4] = b"\xff\xff\xff\xff"
        p[f"seq_{k}"] = write_png(f"seq_48x48_{k}.png", 48, 48, bytes(fr), False,
                                  tags=["opaque", "anim-frame"], notes=f"연속 프레임 {k}")
    return p


# ---------------------------------------------------------------------------
# 메타데이터 페이로드 (EXIF, XMP)
# ---------------------------------------------------------------------------


def exif_tiff(byte_order: str, orientation: int) -> bytes:
    """TIFF 헤더부터 시작하는 EXIF 페이로드. IFD0: Orientation(0x0112), Software(0x0131).

    libwebp imageio는 JPEG APP1의 'Exif\\0\\0' 6바이트를 떼고 TIFF 헤더부터 저장한다
    (research/0005 "EXIF 페이로드 형태"). 그 형태를 기본으로 한다.
    """
    e = "<" if byte_order == "II" else ">"
    software = b"webp-imageio-external-server-proto samples\x00"
    n = 2
    ifd_size = 2 + n * 12 + 4
    data_off = 8 + ifd_size
    out = byte_order.encode("ascii") + struct.pack(e + "HI", 42, 8)
    out += struct.pack(e + "H", n)
    # SHORT 1개는 값 필드(4바이트)의 앞 2바이트에 왼쪽 정렬해 넣는다(TIFF 6.0).
    out += struct.pack(e + "HHI", 0x0112, 3, 1) + struct.pack(e + "HH", orientation, 0)
    out += struct.pack(e + "HHII", 0x0131, 2, len(software), data_off)
    out += struct.pack(e + "I", 0)
    out += software
    return out


XMP_BODY = (
    '<x:xmpmeta xmlns:x="adobe:ns:meta/">\n'
    ' <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">\n'
    '  <rdf:Description rdf:about=""\n'
    '    xmlns:dc="http://purl.org/dc/elements/1.1/"\n'
    '    xmlns:tiff="http://ns.adobe.com/tiff/1.0/">\n'
    '   <tiff:Orientation>6</tiff:Orientation>\n'
    '   <dc:title><rdf:Alt><rdf:li xml:lang="x-default">'
    'webp-imageio 샘플 XMP</rdf:li></rdf:Alt></dc:title>\n'
    '  </rdf:Description>\n'
    ' </rdf:RDF>\n'
    '</x:xmpmeta>\n'
)


def make_metadata_inputs() -> dict[str, str]:
    """EXIF/XMP 페이로드 파일. ICC는 third_party/ 파일을 그대로 쓴다."""
    d = INPUTS / "meta"
    p = {}
    payloads = {
        "exif_ii": ("exif_ii_orientation6.exif", exif_tiff("II", 6),
                    "TIFF little-endian, Orientation=6", ["exif"]),
        "exif_mm": ("exif_mm_orientation8.exif", exif_tiff("MM", 8),
                    "TIFF big-endian, Orientation=8", ["exif"]),
        "exif_prefixed": ("exif_prefixed_ii.exif", b"Exif\x00\x00" + exif_tiff("II", 3),
                          "'Exif\\0\\0' 접두어 + TIFF LE, Orientation=3", ["exif"]),
        "xmp_xpacket": ("xmp_xpacket.xmp",
                        ('<?xpacket begin="﻿" id="W5M0MpCehiHzreSzNTczkc9d"?>\n'
                         + XMP_BODY + '<?xpacket end="w"?>').encode("utf-8"),
                        "xpacket 래퍼 포함, UTF-8(한글 포함)", ["xmp"]),
        "xmp_bare": ("xmp_bare.xmp", XMP_BODY.encode("utf-8"),
                     "xpacket 래퍼 없음", ["xmp"]),
    }
    for key, (name, data, notes, tags) in payloads.items():
        path = d / name
        sl.write_bytes(path, data)
        record(path, tags=["input", "metadata"] + tags, rfc=["§2.7.1.5"], tools=[],
               script="make_metadata_inputs", notes=notes)
        p[key] = sl.rel(path)
    return p


def check_third_party() -> None:
    """커밋된 third_party 파일이 고정한 sha256과 같은지 확인하고 출처를 기록한다."""
    for path_str, (src, digest) in COMPACT_ICC["files"].items():
        path = sl.SAMPLES_DIR / path_str
        if not path.exists() or sl.sha256_file(path) != digest:
            raise SystemExit(f"{path_str} missing or sha256 mismatch (expected {digest})")
        entry = {
            "path": path_str,
            "origin": {
                "type": "download",
                "url": f"{COMPACT_ICC['repo']}/blob/{COMPACT_ICC['commit']}/{src}",
                "repo": COMPACT_ICC["repo"],
                "commit": COMPACT_ICC["commit"],
                "repo_path": src,
            },
            "license": "CC0-1.0",
            "tags": sorted(["third-party", "icc" if path_str.endswith(".icc") else "license"]),
            "rfc": ["§2.7.1.4"] if path_str.endswith(".icc") else [],
            "generator": {"download": "fetched 2026-10-23; git blob SHA-1 matched the tree of "
                                      "the pinned commit; sha256 pinned here"},
        }
        entry["notes"] = ("Compact-ICC-Profiles 원본 그대로(변경 없음)" if path_str.endswith(".icc")
                          else "Compact-ICC-Profiles license 파일(CC0 1.0 전문)")
        records.append(entry)


# ---------------------------------------------------------------------------
# 정지 이미지
# ---------------------------------------------------------------------------


def cwebp(src: str, out: Path, *opts: str) -> str:
    return tool(["cwebp", "-quiet", *opts, src, "-o", sl.rel(out)])


def make_still(p: dict[str, str]) -> None:
    d = GEN / "still"
    cases = [
        # (파일, 입력, cwebp 옵션, 태그, RFC 절, 비고)
        ("lossy_q0.webp", "rgb_64x48", ["-q", "0"], ["lossy"], ["§2.5"], "품질 0"),
        ("lossy_q75.webp", "rgb_64x48", ["-q", "75"], ["lossy"], ["§2.5"], "품질 75(cwebp 기본)"),
        ("lossy_q100.webp", "rgb_64x48", ["-q", "100"], ["lossy"], ["§2.5"], "품질 100"),
        ("lossless_z0.webp", "rgb_64x48", ["-lossless", "-z", "0"], ["lossless"], ["§2.6", "§3.4"],
         "lossless 최저 노력. 불투명이라 입력 RGBA와 정확히 같아야 한다"),
        ("lossless_z9.webp", "rgb_64x48", ["-lossless", "-z", "9"], ["lossless"], ["§2.6", "§3.4"],
         "lossless 최고 노력"),
        ("near_lossless_60.webp", "rgb_64x48", ["-lossless", "-near_lossless", "60"],
         ["lossless", "near-lossless"], ["§2.6"], "near-lossless 전처리 60(픽셀이 입력과 다르다)"),
        ("lossy_alpha_m0.webp", "rgba_64x48", ["-q", "75", "-alpha_method", "0"],
         ["lossy", "alpha"], ["§2.7", "§2.7.1.2"], "ALPH 무압축(C=0)"),
        ("lossy_alpha_m1.webp", "rgba_64x48",
         ["-q", "75", "-alpha_method", "1", "-alpha_filter", "best"], ["lossy", "alpha"],
         ["§2.7", "§2.7.1.2"], "ALPH lossless 압축(C=1), 필터 best"),
        ("lossy_alpha_exact.webp", "rgba_64x48", ["-q", "75", "-exact"], ["lossy", "alpha"],
         ["§2.7", "§2.7.1.2"], "-exact: 투명 영역 RGB 보존"),
        ("lossless_alpha.webp", "rgba_64x48", ["-lossless"], ["lossless", "alpha"],
         ["§2.6", "§3.4"], "-exact 없음: alpha=0 픽셀의 RGB가 입력과 다를 수 있다"),
        ("lossless_alpha_exact.webp", "rgba_64x48", ["-lossless", "-exact"],
         ["lossless", "alpha"], ["§2.6", "§3.4"], "-exact: 입력 RGBA와 정확히 같아야 한다"),
        ("lossy_1x1.webp", "rgb_1x1", ["-q", "75"], ["lossy", "tiny"], ["§2.5"], "1x1"),
        ("lossless_alpha_1x1.webp", "rgba_1x1", ["-lossless", "-exact"],
         ["lossless", "alpha", "tiny"], ["§2.6"], "1x1 RGBA"),
        ("lossy_alpha_1x1.webp", "rgba_1x1", ["-q", "75", "-exact"], ["lossy", "alpha", "tiny"],
         ["§2.7", "§2.7.1.2"], "1x1 lossy + alpha"),
        ("lossless_1x13.webp", "rgb_1x13", ["-lossless", "-exact"], ["lossless", "tiny"],
         ["§2.6"], "폭 1"),
        ("lossless_13x1.webp", "rgb_13x1", ["-lossless", "-exact"], ["lossless", "tiny"],
         ["§2.6"], "높이 1"),
        ("lossy_1x13.webp", "rgb_1x13", ["-q", "75"], ["lossy", "tiny"], ["§2.5"], "폭 1"),
        ("lossy_17x9.webp", "rgb_17x9", ["-q", "75"], ["lossy", "odd-size"], ["§2.5"], "홀수 크기"),
        ("lossless_alpha_17x9.webp", "rgba_17x9", ["-lossless", "-exact"],
         ["lossless", "alpha", "odd-size"], ["§2.6"], "홀수 크기 + alpha"),
        ("lossless_3840x2160.webp", "gradient_3840x2160", ["-lossless", "-exact"],
         ["lossless", "large", "over-4mib"], ["§2.6"],
         "파일은 작고 디코딩 RGBA는 33,177,600 B(4 MiB 초과). 청크 스트리밍 검증용"),
        ("lossy_3840x2160.webp", "gradient_3840x2160", ["-q", "75"],
         ["lossy", "large", "over-4mib"], ["§2.5"], "lossy 4K, 디코딩 RGBA 33,177,600 B"),
        ("lossy_16383x1.webp", "ramp_16383x1", ["-q", "75"], ["lossy", "edge"], ["§2.5"],
         "VP8 최대 폭 16383"),
        ("lossless_16383x1.webp", "ramp_16383x1", ["-lossless", "-exact"], ["lossless", "edge"],
         ["§2.6", "§3.4"], "인코더 최대 폭 16383"),
    ]
    for name, src, opts, tags, rfc, notes in cases:
        out = d / name
        cmd = cwebp(p[src], out, *opts)
        record(out, tags=["still"] + tags, rfc=rfc, tools=["cwebp"], commands=[cmd],
               notes=notes, source=p[src])

    # VP8L 헤더 폭 16384(RFC 9649 §3.4가 허용하는 최댓값, 인코더 한도 16383 초과).
    # 단색 입력은 팔레트 1색 + 8픽셀 묶음이라 압축 데이터가 폭과 무관하다(ceil(16383/8) =
    # ceil(16384/8) = 2048). 그래서 16383x1을 인코딩한 뒤 헤더 폭만 1 늘려도 유효하다.
    seed = TMP / "solid_16383x1.webp"
    cmd1 = cwebp(p["solid_16383x1"], seed, "-lossless", "-exact")
    chunks = sl.parse_chunks(seed.read_bytes())
    if [f for f, _ in chunks] != [b"VP8L"]:
        raise SystemExit("unexpected chunk layout for solid 16383x1")
    payload = bytearray(chunks[0][1])
    bits = struct.unpack("<I", payload[1:5])[0]
    if (bits & 0x3FFF) + 1 != 16383:
        raise SystemExit("unexpected VP8L width")
    bits = (bits & ~0x3FFF) | (16384 - 1)
    payload[1:5] = struct.pack("<I", bits)
    out = d / "lossless_16384x1_header_patched.webp"
    sl.write_bytes(out, sl.build_webp([(b"VP8L", bytes(payload))]))
    record(out, tags=["still", "lossless", "edge"], rfc=["§3.4"], tools=["cwebp"],
           commands=[cmd1], script="patch VP8L header width 16383 -> 16384",
           notes="VP8L 폭 16384(포맷 최대, 인코더는 16383까지). 단색 16383x1 출력의 헤더만 패치",
           source=p["solid_16383x1"])


# ---------------------------------------------------------------------------
# 애니메이션
# ---------------------------------------------------------------------------


def encode_frames(p: dict[str, str]) -> tuple[dict[str, str], dict[str, str]]:
    """프레임용 비애니메이션 WebP를 .build/frames/에 만든다. webpmux -frame 입력이다."""
    frames, cmds = {}, {}
    specs = {
        "bg_ll": ("anim_bg", ["-lossless", "-exact"]),
        "overlay_ll": ("anim_overlay", ["-lossless", "-exact"]),
        "small_ll": ("anim_small", ["-lossless", "-exact"]),
        "bg_lossy": ("anim_bg", ["-q", "75"]),
        "overlay_lossy": ("anim_overlay", ["-q", "75"]),
        "small_lossy": ("anim_small", ["-q", "75"]),
    }
    for key, (src, opts) in specs.items():
        out = FRAMES / f"{key}.webp"
        cmds[key] = cwebp(p[src], out, *opts)
        frames[key] = sl.rel(out)
    return frames, cmds


def frame_arg(duration: int, x: int, y: int, dispose_bg: bool, blend: bool) -> str:
    """webpmux -frame 인자 '+d+x+y+m+b'. m: 0=none, 1=background. +b=blend, -b=no blend."""
    return f"+{duration}+{x}+{y}+{1 if dispose_bg else 0}{'+b' if blend else '-b'}"


def webpmux_anim(out: Path, frames: list[tuple[str, str]], loop: int, bgcolor: str) -> str:
    args = ["webpmux"]
    for f, spec in frames:
        args += ["-frame", f, spec]
    args += ["-loop", str(loop), "-bgcolor", bgcolor, "-o", sl.rel(out)]
    return tool(args)


# 배경색을 불투명 자홍(A,R,G,B = 255,255,0,255)으로 둔다. libwebp는 이 값을 무시하고
# 투명으로 초기화·dispose한다(anim_decode.c ZeroFillCanvas/ZeroFillFrameRect). RFC 9649
# §2.7.1.1은 배경색을 hint로 둔다. 다른 디코더가 이 색을 쓰면 결과가 눈에 띄게 다르다.
BGCOLOR = "255,255,0,255"


def make_anim(p: dict[str, str]) -> None:
    d = GEN / "anim"
    fr, fcmds = encode_frames(p)

    # blend x dispose 4조합: 배경(전체) -> 대상 프레임(부분, 반투명 + 투명 구멍) -> 겹치는 작은 프레임.
    # 세 번째 프레임이 그려질 때 두 번째 프레임의 dispose 결과가 드러난다.
    for blend in (True, False):
        for dispose_bg in (False, True):
            name = (f"bd_{'blend' if blend else 'noblend'}_"
                    f"{'dispose-bg' if dispose_bg else 'dispose-none'}.webp")
            out = d / name
            frames = [
                (fr["bg_ll"], frame_arg(100, 0, 0, False, False)),
                (fr["overlay_ll"], frame_arg(100, 8, 8, dispose_bg, blend)),
                (fr["small_ll"], frame_arg(100, 24, 24, False, True)),
            ]
            cmd = webpmux_anim(out, frames, 0, BGCOLOR)
            record(out, tags=["anim", "lossless", "alpha", "blend-dispose"],
                   rfc=["§2.7.1.1", "§2.7.2"], tools=["cwebp", "webpmux"],
                   commands=[fcmds["bg_ll"], fcmds["overlay_ll"], fcmds["small_ll"], cmd],
                   notes=(f"2번째 프레임: blend={'yes' if blend else 'no'}, "
                          f"dispose={'background' if dispose_bg else 'none'}. "
                          "배경색 자홍은 libwebp가 무시한다"))

    for loop in (0, 1, 65535):
        out = d / f"loop_{loop}.webp"
        frames = [(fr["bg_ll"], frame_arg(100, 0, 0, False, False)),
                  (fr["small_ll"], frame_arg(100, 16, 16, False, True))]
        cmd = webpmux_anim(out, frames, loop, BGCOLOR)
        record(out, tags=["anim", "lossless", "loop"], rfc=["§2.7.1.1"],
               tools=["cwebp", "webpmux"], commands=[fcmds["bg_ll"], fcmds["small_ll"], cmd],
               notes=f"loop count {loop}" + (" (무한)" if loop == 0 else ""))

    # duration 경계: 0, 1, 10, 11, 100, 2^24-1. RFC는 0과 10 이하의 해석을 구현에 맡긴다.
    out = d / "durations.webp"
    durations = [0, 1, 10, 11, 100, 16777215]
    positions = [(0, 0), (0, 0), (16, 16), (32, 32), (8, 8), (24, 0)]
    keys = ["bg_ll", "small_ll", "small_ll", "small_ll", "overlay_ll", "small_ll"]
    frames = [(fr[k], frame_arg(dur, x, y, False, True))
              for k, dur, (x, y) in zip(keys, durations, positions)]
    cmd = webpmux_anim(out, frames, 0, BGCOLOR)
    record(out, tags=["anim", "lossless", "duration"], rfc=["§2.7.1.1"],
           tools=["cwebp", "webpmux"],
           commands=[fcmds["bg_ll"], fcmds["small_ll"], fcmds["overlay_ll"], cmd],
           notes="프레임 duration 0, 1, 10, 11, 100, 16777215 ms")

    # 혼합 코덱: lossy(VP8), lossless(VP8L), lossy+alpha(ALPH+VP8), lossless+alpha.
    out = d / "mixed_codecs.webp"
    frames = [
        (fr["bg_lossy"], frame_arg(100, 0, 0, False, False)),
        (fr["small_ll"], frame_arg(100, 4, 4, False, True)),
        (fr["overlay_lossy"], frame_arg(100, 20, 20, True, True)),
        (fr["overlay_ll"], frame_arg(100, 2, 22, False, True)),
    ]
    cmd = webpmux_anim(out, frames, 0, BGCOLOR)
    record(out, tags=["anim", "lossy", "lossless", "alpha", "mixed"],
           rfc=["§2.7.1.1", "§2.7.1.2", "§2.7.1.3"], tools=["cwebp", "webpmux"],
           commands=[fcmds["bg_lossy"], fcmds["small_ll"], fcmds["overlay_lossy"],
                     fcmds["overlay_ll"], cmd],
           notes="ANMF 안에 VP8 / VP8L / ALPH+VP8 / VP8L(alpha)이 섞인다")

    # 오프셋: 홀수 요청(+7+5)은 webpmux가 짝수로 내린다(6,4). 캔버스 오른쪽 아래 끝에 붙은 프레임.
    out = d / "offsets.webp"
    frames = [
        (fr["bg_ll"], frame_arg(100, 0, 0, False, False)),
        (fr["small_ll"], frame_arg(100, 7, 5, False, True)),
        (fr["small_ll"], frame_arg(100, 32, 32, False, True)),
        (fr["overlay_ll"], frame_arg(100, 24, 0, True, False)),
    ]
    cmd = webpmux_anim(out, frames, 0, BGCOLOR)
    record(out, tags=["anim", "lossless", "offset"], rfc=["§2.7.1.1", "§2.7.2"],
           tools=["cwebp", "webpmux"],
           commands=[fcmds["bg_ll"], fcmds["small_ll"], fcmds["overlay_ll"], cmd],
           notes="홀수 오프셋 요청(7,5)은 (6,4)로 저장된다. (32,32) 16x16 프레임은 캔버스 끝에 맞닿는다")

    # webpmux는 캔버스 크기를 따로 지정하지 않으면 프레임 1개짜리 애니메이션을 항상
    # 비애니메이션 파일로 바꾼다(libwebp 1.6.0 src/mux/muxedit.c MuxCleanup). 그래서 직접 조립한다.
    out = d / "single_frame.webp"
    frame_chunks = sl.parse_chunks((sl.SAMPLES_DIR / fr["overlay_ll"]).read_bytes())
    if [f for f, _ in frame_chunks] != [b"VP8L"]:
        raise SystemExit("unexpected frame layout")
    anim_payload = bytes((255, 0, 255, 255)) + struct.pack("<H", 0)  # [B,G,R,A] + loop 0
    sl.write_bytes(out, sl.build_webp([
        (b"VP8X", sl.vp8x_payload(sl.VP8X_ANIM | sl.VP8X_ALPHA, 24, 24)),
        (b"ANIM", anim_payload),
        (b"ANMF", sl.anmf_payload(0, 0, 24, 24, 250, False, False,
                                  sl.chunk(b"VP8L", frame_chunks[0][1]))),
    ]))
    record(out, tags=["anim", "lossless", "alpha", "single-frame", "crafted"], rfc=["§2.7.1.1"],
           tools=["cwebp"], commands=[fcmds["overlay_ll"]],
           script="assemble VP8X(A,L) + ANIM + one ANMF from the cwebp frame",
           notes="ANMF 1개짜리 애니메이션(24x24, 250 ms, blend). webpmux로는 만들 수 없어 직접 조립")

    # WebPAnimEncoder가 직접 고른 부분 사각형·blend·dispose(실제 인코더 출력 형태).
    out = d / "img2webp_mixed.webp"
    args = ["img2webp", "-loop", "0", "-mixed", "-d", "80", p["seq_0"], p["seq_1"], p["seq_2"],
            "-o", sl.rel(out)]
    cmd = tool(args)
    record(out, tags=["anim", "mixed", "encoder-chosen"], rfc=["§2.7.1.1", "§2.7.2"],
           tools=["img2webp"], commands=[cmd],
           notes="img2webp -mixed: 프레임 사각형과 blend/dispose를 인코더가 고른다")


# ---------------------------------------------------------------------------
# 메타데이터와 청크 배치
# ---------------------------------------------------------------------------


def webpmux_set(src: str, out: Path, *sets: tuple[str, str]) -> list[str]:
    """webpmux -set을 차례로 적용한다(한 번에 하나만 받으므로 중간 파일을 거친다)."""
    cmds = []
    cur = src
    for i, (kind, payload) in enumerate(sets):
        dst = out if i == len(sets) - 1 else TMP / f"{out.stem}.step{i}.webp"
        cmds.append(tool(["webpmux", "-set", kind, payload, cur, "-o", sl.rel(dst)]))
        cur = sl.rel(dst)
    return cmds


def make_meta(p: dict[str, str], m: dict[str, str]) -> None:
    d = GEN / "meta"
    still = GEN / "still"
    lossy = sl.rel(still / "lossy_q75.webp")
    cases = [
        ("lossy_icc_srgb.webp", lossy, [("icc", ICC_SRGB)], ["icc"], ["§2.7.1.4"],
         "ICCP: Compact-ICC sRGB v4"),
        ("lossy_exif_ii.webp", lossy, [("exif", m["exif_ii"])], ["exif"], ["§2.7.1.5"],
         "EXIF TIFF LE, Orientation=6"),
        ("lossy_exif_mm.webp", lossy, [("exif", m["exif_mm"])], ["exif"], ["§2.7.1.5"],
         "EXIF TIFF BE, Orientation=8"),
        ("lossy_exif_prefixed.webp", lossy, [("exif", m["exif_prefixed"])], ["exif"],
         ["§2.7.1.5"], "EXIF 'Exif\\0\\0' 접두어 변형"),
        ("lossy_xmp.webp", lossy, [("xmp", m["xmp_xpacket"])], ["xmp"], ["§2.7.1.5"],
         "XMP xpacket 래퍼 포함"),
        ("lossy_xmp_bare.webp", lossy, [("xmp", m["xmp_bare"])], ["xmp"], ["§2.7.1.5"],
         "XMP xpacket 없음"),
        ("lossy_icc_exif_xmp.webp", lossy,
         [("icc", ICC_P3), ("exif", m["exif_ii"]), ("xmp", m["xmp_xpacket"])],
         ["icc", "exif", "xmp"], ["§2.7.1.4", "§2.7.1.5", "§2.7.3"], "Display P3 + EXIF + XMP"),
        ("lossless_alpha_icc_exif_xmp.webp", sl.rel(still / "lossless_alpha_exact.webp"),
         [("icc", ICC_SRGB), ("exif", m["exif_mm"]), ("xmp", m["xmp_bare"])],
         ["icc", "exif", "xmp", "lossless", "alpha"], ["§2.7.1.4", "§2.7.1.5"],
         "lossless+alpha + 메타데이터 3종"),
        ("anim_icc_exif_xmp.webp", sl.rel(GEN / "anim" / "bd_blend_dispose-bg.webp"),
         [("icc", ICC_SRGB), ("exif", m["exif_ii"]), ("xmp", m["xmp_xpacket"])],
         ["icc", "exif", "xmp", "anim"], ["§2.7.1.4", "§2.7.1.5", "§2.7.3"],
         "애니메이션 + 메타데이터 3종"),
    ]
    for name, src, sets, tags, rfc, notes in cases:
        out = d / name
        cmds = webpmux_set(src, out, *sets)
        record(out, tags=["meta"] + tags, rfc=rfc, tools=["cwebp", "webpmux"],
               commands=cmds, notes=notes, source=src)

    # 아래는 도구로 만들 수 없는 청크 배치라 RIFF를 직접 조립한다.
    base = sl.parse_chunks((d / "lossy_icc_exif_xmp.webp").read_bytes())
    kinds = [f for f, _ in base]
    if kinds != [b"VP8X", b"ICCP", b"VP8 ", b"EXIF", b"XMP "]:
        raise SystemExit(f"unexpected layout from webpmux: {kinds}")
    by = dict(base)
    seed = "generated/meta/lossy_icc_exif_xmp.webp"

    def craft(name: str, chunks: list[tuple[bytes, bytes]], step: str, tags: list[str],
              rfc: list[str], notes: str) -> None:
        out = d / name
        sl.write_bytes(out, sl.build_webp(chunks))
        record(out, tags=["meta", "crafted"] + tags, rfc=rfc, tools=[], script=step,
               notes=notes, source=seed)

    # RFC 9649 §2.7.1.6: unknown 청크는 파일 끝에 올 수 있고 reader는 무시해야 한다(SHOULD).
    craft("unknown_chunk_end.webp",
          base + [(b"UNKN", b"unknown chunk payload, readers SHOULD ignore\x00")],
          "append chunk 'UNKN' at end", ["unknown-chunk"], ["§2.7.1.6"],
          "파일 끝에 unknown 청크 'UNKN'(46 B)")
    # RFC 9649 §2.7: 메타데이터(EXIF/XMP)는 이미지 앞에 와도 된다(MAY).
    craft("metadata_before_image.webp",
          [(b"VP8X", by[b"VP8X"]), (b"ICCP", by[b"ICCP"]), (b"EXIF", by[b"EXIF"]),
           (b"XMP ", by[b"XMP "]), (b"VP8 ", by[b"VP8 "])],
          "reorder: EXIF, XMP before VP8", ["icc", "exif", "xmp", "chunk-order"], ["§2.7"],
          "EXIF/XMP를 VP8 앞에 둔다(RFC MAY)")
    # RFC 9649 §2.7.1.5: 종류마다 1개를 SHOULD, 여러 개면 첫 번째만 써도 된다(MAY).
    second_exif = (sl.SAMPLES_DIR / m["exif_mm"]).read_bytes()
    craft("duplicate_exif.webp",
          base + [(b"EXIF", second_exif)],
          "append second EXIF chunk", ["exif", "duplicate"], ["§2.7.1.5"],
          "EXIF 2개: 첫 번째 LE(Orientation 6), 두 번째 BE(Orientation 8)")
    # RFC 9649 §2.7.1.4: ICCP는 1개를 SHOULD, 여러 개면 첫 번째 외는 무시해도 된다(MAY).
    second_icc = (sl.SAMPLES_DIR / ICC_SRGB).read_bytes()
    craft("duplicate_iccp.webp",
          [(b"VP8X", by[b"VP8X"]), (b"ICCP", by[b"ICCP"]), (b"ICCP", second_icc),
           (b"VP8 ", by[b"VP8 "]), (b"EXIF", by[b"EXIF"]), (b"XMP ", by[b"XMP "])],
          "insert second ICCP chunk", ["icc", "duplicate"], ["§2.7.1.4"],
          "ICCP 2개: 첫 번째 Display P3, 두 번째 sRGB")

    # RFC 9649 §2.7.1.6: unknown 청크는 ANMF Frame Data 끝에도 올 수 있다.
    anim = sl.parse_chunks((GEN / "anim" / "single_frame.webp").read_bytes())
    kinds = [f for f, _ in anim]
    if kinds != [b"VP8X", b"ANIM", b"ANMF"]:
        raise SystemExit(f"unexpected anim layout: {kinds}")
    anmf = anim[2][1] + sl.chunk(b"UNKN", b"frame-level unknown chunk\x00")
    out = d / "unknown_chunk_in_anmf.webp"
    sl.write_bytes(out, sl.build_webp([anim[0], anim[1], (b"ANMF", anmf)]))
    record(out, tags=["meta", "crafted", "anim", "unknown-chunk"], rfc=["§2.7.1.1", "§2.7.1.6"],
           tools=[], script="append chunk 'UNKN' inside ANMF frame data",
           notes="ANMF Frame Data 끝에 unknown 청크", source="generated/anim/single_frame.webp")


def main() -> None:
    sl.require_libwebp_version()
    for sub in ("inputs", "still", "anim", "meta"):
        sl.clean_dir(GEN / sub)
    sl.clean_dir(FRAMES)
    sl.clean_dir(TMP)
    check_third_party()
    p = make_inputs()
    m = make_metadata_inputs()
    make_still(p)
    make_anim(p)
    make_meta(p, m)
    sl.remove_dir(FRAMES)
    sl.remove_dir(TMP)
    out = sl.write_records("generated", records)
    print(f"generate: {len(records)} records -> {sl.rel(out)}")


if __name__ == "__main__":
    main()
