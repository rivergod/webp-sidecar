#!/usr/bin/env python3
"""벤치마크용 합성 이미지 세트를 samples/bench/synthetic/에 만든다(커밋하지 않음, ADR-0006·ADR-0015).

도구 이미지 안에서 실행한다:
    python3 -I samples/scripts/bench_synth.py            # XS~XL
    python3 -I samples/scripts/bench_synth.py --xxl      # XXL(8192x8192)까지
    python3 -I samples/scripts/bench_synth.py --classes XS,S

- 크기 클래스: XS 64x64, S 256x256, M 1024x768, L 1920x1080, XL 4000x3000, XXL 8192x8192(opt-in).
- 인코딩 모드: lossy q75(cwebp -q 75), lossless(cwebp -lossless), lossy+alpha(cwebp -q 75, RGBA 입력).
- 내용: 2축 그래디언트 + 결정적 노이즈(SHAKE-256(seed, 행 번호), 채널당 0..63). 같은 seed면 어디서나
  같은 PNG가 나온다. 합성 내용은 압축률이 사진과 다르므로 사진 세트와 함께 쓴다(사진은 Phase 4).
- 결과 목록과 해시: bench/synthetic/index.json.
"""

from __future__ import annotations

import argparse
import hashlib
import sys
from pathlib import Path

sys.dont_write_bytecode = True
sys.path.insert(0, str(Path(__file__).resolve().parent))

import sampleslib as sl  # noqa: E402

OUT = sl.SAMPLES_DIR / "bench" / "synthetic"
CLASSES = {
    "XS": (64, 64),
    "S": (256, 256),
    "M": (1024, 768),
    "L": (1920, 1080),
    "XL": (4000, 3000),
    "XXL": (8192, 8192),
}
DEFAULT_CLASSES = ["XS", "S", "M", "L", "XL"]
MODES = {
    # 모드: (입력 종류, cwebp 옵션)
    "lossy_q75": ("rgb", ["-q", "75"]),
    "lossless": ("rgb", ["-lossless"]),
    "lossy_alpha_q75": ("rgba", ["-q", "75"]),
}
# 바이트를 0..63으로 줄이는 변환표. 그래디언트(0..191)와 더해도 255를 넘지 않는다.
MASK63 = bytes(b & 0x3F for b in range(256))


def synth_rgba(w: int, h: int, seed: bytes, alpha: bool) -> bytes:
    """그래디언트 + 노이즈 RGBA8. 행마다 큰 정수 덧셈으로 바이트별 합을 한 번에 구한다
    (각 바이트 합이 255 이하라 자리올림이 없다). 순수 파이썬 픽셀 루프보다 훨씬 빠르다."""
    r_row = bytes(x * 191 // max(1, w - 1) for x in range(w))
    diag = bytes(k * 191 // max(1, w + h - 2) for k in range(w + h - 1))
    zeros = bytes(w)
    out = bytearray()
    for y in range(h):
        base = bytearray(w * 4)
        base[0::4] = r_row
        base[1::4] = bytes([y * 191 // max(1, h - 1)]) * w
        base[2::4] = diag[y:y + w]
        # alpha: 불투명이면 255, 아니면 대각선 방향 64..255 그래디언트(노이즈 없음).
        base[3::4] = bytes(64 + v for v in diag[y:y + w]) if alpha else b"\xff" * w
        noise = bytearray(hashlib.shake_256(seed + y.to_bytes(4, "little")).digest(w * 4)
                          .translate(MASK63))
        noise[3::4] = zeros
        row = (int.from_bytes(base, "little") + int.from_bytes(noise, "little")).to_bytes(
            w * 4, "little")
        out += row
    return bytes(out)


def main() -> None:
    ap = argparse.ArgumentParser(description="generate synthetic benchmark images (not committed)")
    ap.add_argument("--classes", default=",".join(DEFAULT_CLASSES),
                    help="comma-separated size classes (default: XS..XL)")
    ap.add_argument("--xxl", action="store_true", help="include XXL 8192x8192 (slow, large)")
    ap.add_argument("--seed", default=sl.DEFAULT_SEED.decode("ascii"), help="noise seed")
    args = ap.parse_args()
    classes = [c.strip() for c in args.classes.split(",") if c.strip()]
    if args.xxl and "XXL" not in classes:
        classes.append("XXL")
    unknown = [c for c in classes if c not in CLASSES]
    if unknown:
        raise SystemExit(f"unknown size class: {unknown}")

    sl.require_libwebp_version()
    sl.clean_dir(OUT)
    seed = args.seed.encode("utf-8")
    entries = []
    for cls in classes:
        w, h = CLASSES[cls]
        pngs = {}
        for kind in ("rgb", "rgba"):
            rgba = synth_rgba(w, h, seed + b"/" + cls.encode("ascii"), alpha=(kind == "rgba"))
            path = OUT / f"{cls}_{w}x{h}_{kind}.png"
            sl.write_bytes(path, sl.encode_png(w, h, rgba, kind == "rgba", level=6))
            pngs[kind] = sl.rel(path)
            entries.append({"path": sl.rel(path), "class": cls, "width": w, "height": h,
                            "mode": f"source_{kind}", "rgba_sha256": sl.sha256_bytes(rgba)})
            del rgba
        for mode, (kind, opts) in MODES.items():
            path = OUT / f"{cls}_{w}x{h}_{mode}.webp"
            args_ = ["cwebp", "-quiet", *opts, pngs[kind], "-o", sl.rel(path)]
            sl.run(args_, timeout=3600)
            entries.append({"path": sl.rel(path), "class": cls, "width": w, "height": h,
                            "mode": mode, "command": sl.command_string(args_), "source": pngs[kind]})
        print(f"bench: {cls} {w}x{h} done")
    for e in entries:
        p = sl.SAMPLES_DIR / e["path"]
        e["sha256"] = sl.sha256_file(p)
        e["size"] = p.stat().st_size
    index = {"seed": args.seed, "libwebp": sl.LIBWEBP_VERSION, "license": sl.SELF_LICENSE,
             "generator": "scripts/bench_synth.py", "files": sorted(entries, key=lambda e: e["path"])}
    sl.write_text(OUT / "index.json", sl.dump_json(index))
    print(f"bench: {len(entries)} files -> {sl.rel(OUT)}")


if __name__ == "__main__":
    main()
