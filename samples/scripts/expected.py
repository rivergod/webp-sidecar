#!/usr/bin/env python3
"""생성 샘플의 기대값을 libwebp 1.6.0으로 계산한다 (ADR-0006 "기대값", ADR-0004 RGBA8).

도구 이미지 안에서 실행한다:
    python3 -I samples/scripts/expected.py

입력: .build/generated.json (generate.py)  출력: .build/expected.json (manifest.py가 합친다)

계산 방법
- 정지 이미지: `dwebp -pam`의 PAM 헤더를 떼어 낸 RGBA8(비-premultiplied, stride = w*4)의
  SHA-256. 같은 값을 `webp_probe still`(WebPDecode, MODE_RGBA — 서버가 쓸 호출)과
  `dwebp -noasm -pam`(SIMD 끔)으로 다시 계산해 셋이 다르면 실패한다.
- 애니메이션: `webp_probe anim`(WebPAnimDecoder, MODE_RGBA)이 내는 합성 캔버스를 프레임마다
  해시한다. anim_dump는 alpha=0 픽셀의 RGB를 0으로 지운 뒤 저장하므로(examples/anim_util.c
  CleanupTransparentPixels) 기대값으로 쓰지 않고, 같은 정규화를 한 해시와 일치하는지만 확인한다.
  libwebp는 ANIM 배경색을 무시하고 투명(0,0,0,0)으로 초기화·dispose한다(libwebp 동작).
- 메타데이터: WebPDemux가 돌려주는 ICCP/EXIF/XMP 청크 페이로드의 크기와 SHA-256.
"""

from __future__ import annotations

import json
import sys
from pathlib import Path

sys.dont_write_bytecode = True
sys.path.insert(0, str(Path(__file__).resolve().parent))

import sampleslib as sl  # noqa: E402

TMP = sl.BUILD_DIR / "expected-tmp"
FORMAT_NAMES = {0: "mixed", 1: "lossy", 2: "lossless"}


def probe(*args: str) -> dict:
    """webp_probe를 실행해 JSON 결과를 돌려준다. 생성 샘플은 모두 유효해야 하므로 실패는 오류다."""
    proc = sl.run(["webp_probe", *args], check=False)
    out = json.loads(proc.stdout.decode("utf-8"))
    if proc.returncode != 0 or not out.get("ok"):
        raise SystemExit(f"webp_probe {' '.join(args)} failed: {out}")
    return out


def pam_rgba(path: Path) -> tuple[int, int, bytes]:
    """dwebp/anim_dump가 쓴 PAM(P7, DEPTH 4, MAXVAL 255, RGB_ALPHA)에서 헤더를 떼어 낸다."""
    data = path.read_bytes()
    end = data.index(b"ENDHDR\n") + len(b"ENDHDR\n")
    header = dict(
        line.split(" ", 1) for line in data[:end].decode("ascii").splitlines()[1:-1]
    )
    if header.get("DEPTH") != "4" or header.get("MAXVAL") != "255":
        raise SystemExit(f"unexpected PAM header in {path}: {header}")
    w, h = int(header["WIDTH"]), int(header["HEIGHT"])
    body = data[end:]
    if len(body) != w * h * 4:
        raise SystemExit(f"PAM size mismatch in {path}")
    return w, h, body


def zero_transparent(rgba: bytes) -> bytes:
    """anim_dump와 같은 정규화: alpha=0 픽셀을 (0,0,0,0)으로 바꾼다."""
    out = bytearray(rgba)
    for i in range(3, len(out), 4):
        if out[i] == 0:
            out[i - 3:i] = b"\x00\x00\x00"
    return bytes(out)


def still_expected(rel_path: str) -> dict:
    pam = TMP / "still.pam"
    pam_noasm = TMP / "still_noasm.pam"
    raw = TMP / "still.rgba"
    sl.run(["dwebp", "-quiet", "-pam", rel_path, "-o", sl.rel(pam)])
    sl.run(["dwebp", "-quiet", "-noasm", "-pam", rel_path, "-o", sl.rel(pam_noasm)])
    w, h, rgba = pam_rgba(pam)
    _, _, rgba_noasm = pam_rgba(pam_noasm)
    info = probe("still", rel_path, sl.rel(raw))
    direct = raw.read_bytes()
    if not (rgba == rgba_noasm == direct) or (info["width"], info["height"]) != (w, h):
        raise SystemExit(f"decoder outputs differ for {rel_path} (dwebp / -noasm / WebPDecode)")
    return {"width": w, "height": h, "rgba_bytes": len(rgba), "rgba_sha256": sl.sha256_bytes(rgba)}


def anim_expected(rel_path: str, demux: dict) -> dict:
    raw = TMP / "anim.rgba"
    info = probe("anim", rel_path, sl.rel(raw))
    w, h, n = info["canvas_width"], info["canvas_height"], info["decoded_frames"]
    frame_bytes = w * h * 4
    data = raw.read_bytes()
    if len(data) != frame_bytes * n or n != info["frame_count"] or n != demux["frame_count"]:
        raise SystemExit(f"unexpected animation output size for {rel_path}")

    # anim_dump 출력과 비교한다(정규화한 값이 같아야 한다).
    dump_dir = TMP / "anim_dump"
    sl.clean_dir(dump_dir)
    sl.run(["anim_dump", "-folder", sl.rel(dump_dir), "-prefix", "f", "-pam", rel_path])
    dumps = sorted(dump_dir.glob("f*.pam"))
    if len(dumps) != n:
        raise SystemExit(f"anim_dump frame count mismatch for {rel_path}")

    frames = []
    anim_dump_matches = True
    for i in range(n):
        rgba = data[i * frame_bytes:(i + 1) * frame_bytes]
        dw, dh, dumped = pam_rgba(dumps[i])
        normalized = zero_transparent(rgba)
        if (dw, dh) != (w, h) or dumped != normalized:
            raise SystemExit(f"anim_dump differs beyond transparent-pixel cleanup: {rel_path} #{i}")
        meta = demux["frames"][i]
        frame = {
            "index": i,
            "x_offset": meta["x_offset"],
            "y_offset": meta["y_offset"],
            "width": meta["width"],
            "height": meta["height"],
            "duration_ms": meta["duration"],
            "dispose": meta["dispose"],
            "blend": meta["blend"],
            "has_alpha": meta["has_alpha"],
            # WebPAnimDecoderGetNext의 timestamp: 이 프레임까지의 duration 누적(프레임 끝 시각).
            "timestamp_ms": info["timestamps"][i],
            "rgba_sha256": sl.sha256_bytes(rgba),
        }
        if normalized != rgba:
            anim_dump_matches = False
            frame["anim_dump_rgba_sha256"] = sl.sha256_bytes(normalized)
        frames.append(frame)
    return {
        "canvas_width": w,
        "canvas_height": h,
        "frame_rgba_bytes": frame_bytes,
        "loop_count": info["loop_count"],
        "bgcolor": info["bgcolor"],
        "frame_count": n,
        "frames": frames,
        # False면 alpha=0이면서 RGB가 0이 아닌 픽셀이 있어 anim_dump 출력과 다르다.
        "anim_dump_matches": anim_dump_matches,
    }


def metadata_expected(rel_path: str, demux: dict, prefix: Path) -> dict:
    out = {}
    names = {"ICCP": "icc", "EXIF": "exif", "XMP": "xmp"}
    for fourcc, key in names.items():
        count = demux["chunks"][fourcc]
        if count == 0:
            continue
        payloads = []
        for k in range(1, count + 1):
            data = Path(f"{prefix}.{fourcc}.{k}").read_bytes()
            payloads.append({"size": len(data), "sha256": sl.sha256_bytes(data)})
        entry = {"count": count, "size": payloads[0]["size"], "sha256": payloads[0]["sha256"]}
        if count > 1:
            entry["all"] = payloads
        out[key] = entry

    # WebPMux(webpmux -get)로도 같은 페이로드가 나오는지 확인한다. 중복 청크가 있으면
    # WebPMuxCreate가 파일을 거부한다(libwebp 1.6.0 동작). 그 사실을 기대값에 남긴다.
    if out:
        mux_ok = True
        for fourcc, key in names.items():
            if key not in out:
                continue
            dst = TMP / f"mux.{key}"
            proc = sl.run(["webpmux", "-get", key, rel_path, "-o", sl.rel(dst)], check=False)
            if proc.returncode != 0:
                mux_ok = False
                break
            if sl.sha256_file(dst) != out[key]["sha256"]:
                raise SystemExit(f"webpmux and WebPDemux disagree on {key} in {rel_path}")
        out["webpmux_get"] = "ok" if mux_ok else "rejected (WebPMuxCreate failed)"
    return out


def main() -> None:
    sl.require_libwebp_version()
    sl.clean_dir(TMP)
    records = sl.read_records("generated")
    png = {r["path"]: r["source_rgba"] for r in records if "source_rgba" in r}
    results = []
    for r in records:
        path = r["path"]
        if path.endswith(".png"):
            results.append({"path": path, "expected": {
                "reference": "generator pixels (scripts/generate.py)",
                "decode": dict(png[path], rgba_bytes=png[path]["width"] * png[path]["height"] * 4),
            }})
            continue
        if not path.endswith(".webp"):
            continue
        feats = probe("features", path)
        prefix = TMP / "chunk"
        demux = probe("demux", path, sl.rel(prefix))
        exp = {
            "reference": f"libwebp {sl.LIBWEBP_VERSION}",
            "features": {
                "width": feats["width"],
                "height": feats["height"],
                "has_alpha": feats["has_alpha"],
                "has_animation": feats["has_animation"],
                "format": FORMAT_NAMES[feats["format"]],
            },
        }
        if feats["has_animation"]:
            exp["animation"] = anim_expected(path, demux)
        else:
            exp["decode"] = still_expected(path)
            # lossless이고 입력 PNG와 크기가 같을 때만 픽셀 정확 일치 여부를 남긴다.
            src = png.get(r.get("source"))
            dec = exp["decode"]
            if (src and feats["format"] == 2
                    and (src["width"], src["height"]) == (dec["width"], dec["height"])):
                dec["matches_source_rgba"] = dec["rgba_sha256"] == src["rgba_sha256"]
        meta = metadata_expected(path, demux, prefix)
        if meta:
            exp["metadata"] = meta
        results.append({"path": path, "expected": exp})
    sl.remove_dir(TMP)
    out = sl.write_records("expected", results)
    print(f"expected: {len(results)} entries -> {sl.rel(out)}")


if __name__ == "__main__":
    main()
