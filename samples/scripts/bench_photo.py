#!/usr/bin/env python3
"""벤치마크용 사진 세트를 samples/bench/photo/에 만든다(커밋하지 않음, ADR-0006·ADR-0015).

입력 PNG는 bench 모듈의 PhotoSources가 먼저 만든다(public domain 사진을 받아 sha256을 확인하고 자르고 줄인다 —
samples/bench.lock.json). 이 스크립트는 그 PNG를 합성 세트(bench_synth.py)와 같은 cwebp 옵션으로 인코딩한다.
도구 이미지 안에서 실행한다:
    python3 -I samples/scripts/bench_photo.py

- 정지 이미지: samples/bench/photo/<class>_<w>x<h>_{rgb,rgba}.png → 모드별 .webp (bench_synth.MODES)
- 애니메이션: samples/bench/photo/anim/frame_NN.png → anim_<w>x<h>_lossy_q75.webp
  (img2webp -loop 0 -lossy -q 75 -d 100, 프레임 사각형과 blend/dispose는 인코더가 고른다)
- 결과 목록과 해시: bench/photo/index.json
"""

from __future__ import annotations

import json
import sys
from pathlib import Path

sys.dont_write_bytecode = True
sys.path.insert(0, str(Path(__file__).resolve().parent))

import bench_synth  # noqa: E402
import sampleslib as sl  # noqa: E402

OUT = sl.SAMPLES_DIR / "bench" / "photo"
LOCK = sl.SAMPLES_DIR / "bench.lock.json"
ANIM_FRAME_MS = 100


def main() -> None:
    sl.require_libwebp_version()
    sources = OUT / "sources.json"
    if not sources.exists():
        raise SystemExit(f"missing {sl.rel(sources)}; run PhotoSources first (bench/run.sh)")
    with open(sources, encoding="utf-8") as f:
        src = json.load(f)
    with open(LOCK, encoding="utf-8") as f:
        lock = json.load(f)

    entries = []
    for png in src["files"]:
        entries.append(dict(png))
    stills = [e for e in src["files"] if e["mode"].startswith("source_")]
    classes = sorted({e["class"] for e in stills}, key=list(bench_synth.CLASSES).index)
    for cls in classes:
        w, h = bench_synth.CLASSES[cls]
        pngs = {e["mode"].removeprefix("source_"): e["path"] for e in stills if e["class"] == cls}
        for mode, (kind, opts) in bench_synth.MODES.items():
            path = OUT / f"{cls}_{w}x{h}_{mode}.webp"
            args_ = ["cwebp", "-quiet", *opts, pngs[kind], "-o", sl.rel(path)]
            sl.run(args_, timeout=3600)
            entries.append({"path": sl.rel(path), "class": cls, "width": w, "height": h,
                            "mode": mode, "command": sl.command_string(args_), "source": pngs[kind]})
        print(f"bench photo: {cls} {w}x{h} done")

    frames = sorted((e for e in src["files"] if e["mode"] == "anim_frame"), key=lambda e: e["path"])
    if frames:
        w, h = frames[0]["width"], frames[0]["height"]
        path = OUT / f"anim_{w}x{h}_lossy_q75.webp"
        args_ = ["img2webp", "-loop", "0", "-lossy", "-q", "75", "-d", str(ANIM_FRAME_MS),
                 *[e["path"] for e in frames], "-o", sl.rel(path)]
        sl.run(args_, timeout=3600)
        entries.append({"path": sl.rel(path), "class": "anim", "width": w, "height": h,
                        "mode": "anim_lossy_q75", "frames": len(frames),
                        "command": sl.command_string(args_)})
        print(f"bench photo: anim {w}x{h} x{len(frames)} done")

    for e in entries:
        p = sl.SAMPLES_DIR / e["path"]
        e["sha256"] = sl.sha256_file(p)
        e["size"] = p.stat().st_size
    index = {"libwebp": sl.LIBWEBP_VERSION, "source": lock["photo"], "license": lock["photo"]["license"],
             "generator": "scripts/bench_photo.py", "sources_generator": src["generator"],
             "files": sorted(entries, key=lambda e: e["path"])}
    sl.write_text(OUT / "index.json", sl.dump_json(index))
    print(f"bench photo: {len(entries)} files -> {sl.rel(OUT)}")


if __name__ == "__main__":
    main()
