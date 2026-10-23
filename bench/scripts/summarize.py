#!/usr/bin/env python3
"""JMH JSON 결과(bench/run.sh)를 요약 표(summary.md)와 CSV(results.csv)로 만든다.

    python3 -I bench/scripts/summarize.py <결과 디렉터리>

판정(ADR-0011): 디코딩 처리량, M/L/XL, 이 플러그인 기본 전송(UDS)·기본 빌드(sse2-uds) 기준으로
TwelveMonkeys의 3배 이상이고 darkxanter(JNI)의 50% 이상이면 충족.
표의 ±는 JMH가 낸 99.9% 신뢰구간 반폭(scoreError)을 점수 대비 %로 적은 것이다.
"""

from __future__ import annotations

import csv
import json
import sys
from pathlib import Path

SIZES = ["XS", "S", "M", "L", "XL"]
JUDGED_SIZES = {"M", "L", "XL"}
MODES = ["lossy", "lossless", "alpha"]
CONTENTS = ["photo", "synthetic"]
TM_MIN = 3.0
JNI_MIN = 0.5


def number(v) -> float:
    """JMH는 측정이 1회뿐이면 오차를 문자열 "NaN"으로 쓴다. 그때는 0으로 둔다."""
    try:
        f = float(v)
    except (TypeError, ValueError):
        return 0.0
    return 0.0 if f != f else f


def load(results: Path) -> list[dict]:
    rows = []
    for f in sorted(results.glob("*.json")):
        try:
            data = json.loads(f.read_text(encoding="utf-8"))
        except json.JSONDecodeError:
            print(f"skip (not JMH JSON): {f}", file=sys.stderr)
            continue
        for b in data:
            pm = b["primaryMetric"]
            rows.append({
                "suite": f.stem,
                "benchmark": b["benchmark"].rsplit(".", 2)[-2] + "." + b["benchmark"].rsplit(".", 1)[-1],
                "params": b.get("params", {}),
                "score": number(pm["score"]),
                "error": number(pm["scoreError"]),
                "unit": pm["scoreUnit"],
                "forks": b["forks"],
                "iterations": b["measurementIterations"],
            })
    return rows


def find(rows, benchmark, suite=None, **params):
    """조건에 맞는 첫 측정. suite를 주면 그 묶음(JSON 파일)에서만 찾는다 — 비교는 같은 실행 안에서 한다."""
    for r in rows:
        if r["benchmark"] != benchmark or (suite is not None and r["suite"] != suite):
            continue
        if all(r["params"].get(k) == v for k, v in params.items()):
            return r
    return None


def ops(r) -> str:
    if r is None:
        return "—"
    s = r["score"]
    pct = 100 * r["error"] / s if s else 0
    if s >= 100:
        v = f"{s:,.0f}"
    elif s >= 10:
        v = f"{s:.1f}"
    else:
        v = f"{s:.2f}"
    return f"{v} ±{pct:.0f}%"


def ms(r) -> float | None:
    return 1000.0 / r["score"] if r and r["score"] else None


def fmt_ms(v: float | None) -> str:
    if v is None:
        return "—"
    return f"{v:.3f}" if v < 1 else f"{v:.2f}" if v < 100 else f"{v:.0f}"


def ratio(a, b) -> float | None:
    if a is None or b is None or not b["score"]:
        return None
    return a["score"] / b["score"]


def fmt_ratio(v: float | None) -> str:
    return "—" if v is None else f"{v:.2f}"


def decode_section(rows) -> list[str]:
    out = ["## 정지 이미지 디코딩 (ops/s, 높을수록 빠름)", "",
           "서버: `sse2-uds`(기본 빌드, UDS). 판정: M/L/XL에서 대 TwelveMonkeys ≥ 3.0 그리고 대 JNI ≥ 0.50.", ""]
    verdicts = []
    for content in CONTENTS:
        out += [f"### {content}", "",
                "| 모드 | 크기 | 사이드카 | TwelveMonkeys | darkxanter (JNI) | Wasm (Chicory) | 대 TM | 대 JNI | 대 Wasm | 판정 |",
                "|---|---|---|---|---|---|---|---|---|---|"]
        for mode in MODES:
            for size in SIZES:
                p = dict(content=content, size=size, mode=mode)
                sc = find(rows, "DecodeBenchmark.decode", "decode", impl="SIDECAR", server="sse2-uds", **p)
                tm = find(rows, "DecodeBenchmark.decode", "decode", impl="TWELVEMONKEYS", **p)
                dx = find(rows, "DecodeBenchmark.decode", "decode", impl="DARKXANTER", **p)
                wa = find(rows, "DecodeBenchmark.decode", "decode", impl="WASM", **p)
                if not any([sc, tm, dx, wa]):
                    continue
                rt, rj, rw = ratio(sc, tm), ratio(sc, dx), ratio(sc, wa)
                verdict = ""
                if size in JUDGED_SIZES and rt is not None and rj is not None:
                    ok = rt >= TM_MIN and rj >= JNI_MIN
                    verdict = "충족" if ok else "**미달**"
                    verdicts.append((content, mode, size, ok, rt, rj))
                out.append(f"| {mode} | {size} | {ops(sc)} | {ops(tm)} | {ops(dx)} | {ops(wa)} | "
                           f"{fmt_ratio(rt)} | {fmt_ratio(rj)} | {fmt_ratio(rw)} | {verdict} |")
        out.append("")
    if verdicts:
        n_ok = sum(1 for v in verdicts if v[3])
        out += ["### ADR-0011 판정 요약", "",
                f"판정 대상 {len(verdicts)}개 조건 중 {n_ok}개 충족.", ""]
        misses = [v for v in verdicts if not v[3]]
        if misses:
            out.append("미달 조건:")
            for c, m, s, _, rt, rj in misses:
                why = []
                if rt < TM_MIN:
                    why.append(f"대 TM {rt:.2f} < {TM_MIN}")
                if rj < JNI_MIN:
                    why.append(f"대 JNI {rj:.2f} < {JNI_MIN}")
                out.append(f"- {c}/{m}/{s}: " + ", ".join(why))
            out.append("")
    return out


def servers_section(rows) -> list[str]:
    out = ["## 전송 방식과 SIMD 빌드 (사이드카, photo, ops/s)", "",
           "세 서버를 같은 묶음(decode-servers) 안에서 잰 값끼리 비교한다.", "",
           "| 모드 | 크기 | sse2-uds | sse2-tcp | avx2-uds | TCP/UDS | AVX2/SSE2 |", "|---|---|---|---|---|---|---|"]
    any_row = False
    for mode in MODES:
        for size in SIZES:
            p = dict(impl="SIDECAR", content="photo", size=size, mode=mode)
            u = find(rows, "DecodeBenchmark.decode", "decode-servers", server="sse2-uds", **p)
            t = find(rows, "DecodeBenchmark.decode", "decode-servers", server="sse2-tcp", **p)
            a = find(rows, "DecodeBenchmark.decode", "decode-servers", server="avx2-uds", **p)
            if not (t or a):
                continue
            any_row = True
            out.append(f"| {mode} | {size} | {ops(u)} | {ops(t)} | {ops(a)} | {fmt_ratio(ratio(t, u))} | "
                       f"{fmt_ratio(ratio(a, u))} |")
    return out + [""] if any_row else []


def chunk_section(rows) -> list[str]:
    out = ["## 응답 조각 크기 (사이드카 UDS, photo, ops/s)", "", "네 조각 크기를 같은 묶음(decode-chunk) 안에서 잰 값끼리 비교한다.", "",
           "| 모드 | 크기 | 256 KiB | 512 KiB | 1 MiB (기본) | 2 MiB |", "|---|---|---|---|---|---|"]
    any_row = False
    for mode in MODES:
        for size in SIZES:
            p = dict(impl="SIDECAR", content="photo", size=size, mode=mode)
            cells = [find(rows, "DecodeBenchmark.decode", "decode-chunk", server=s, **p)
                     for s in ("sse2-uds-256k", "sse2-uds-512k", "sse2-uds", "sse2-uds-2m")]
            if not (cells[0] or cells[1] or cells[3]):
                continue
            any_row = True
            out.append(f"| {mode} | {size} | " + " | ".join(ops(c) for c in cells) + " |")
    return out + [""] if any_row else []


def wasm_interp_section(rows) -> list[str]:
    out = ["## Wasm 인터프리터 (참고값, photo, ops/s)", "",
           "| 모드 | 크기 | 인터프리터 | 빌드타임 컴파일 | 컴파일/인터프리터 |", "|---|---|---|---|---|"]
    any_row = False
    for mode in MODES:
        for size in SIZES:
            p = dict(content="photo", size=size, mode=mode)
            i = find(rows, "DecodeBenchmark.decode", impl="WASM_INTERPRETER", **p)
            if not i:
                continue
            any_row = True
            c = find(rows, "DecodeBenchmark.decode", "decode", impl="WASM", **p)
            out.append(f"| {mode} | {size} | {ops(i)} | {ops(c)} | {fmt_ratio(ratio(c, i))} |")
    return out + [""] if any_row else []


def load_decode_time(results: Path, build: str) -> dict[str, float]:
    """bench/run.sh decode-time 결과(서버 안 libwebp 디코딩 중앙값, ms). 키는 파일 이름."""
    f = results / f"decode-time-{build}.tsv"
    if not f.exists():
        return {}
    out = {}
    for line in f.read_text(encoding="utf-8").splitlines()[1:]:
        cols = line.split("\t")
        if len(cols) >= 2:
            out[cols[0]] = float(cols[1])
    return out


FILE_MODES = {"lossy": "lossy_q75", "lossless": "lossless", "alpha": "lossy_alpha_q75"}
DIMS = {"XS": (64, 64), "S": (256, 256), "M": (1024, 768), "L": (1920, 1080), "XL": (4000, 3000)}


def breakdown_section(rows, decode_time: dict[str, float]) -> list[str]:
    out = ["## 사이드카 디코딩 시간 분해 (photo, UDS, ms/op)", "",
           "플러그인 전체(DecodeBenchmark SIDECAR) = GetFeatures RPC + Decode RPC + 클라이언트 몫(변환 + ImageIO). "
           "Decode RPC = 서버 libwebp 디코딩 + 전송·기타. 클라이언트 몫은 전체에서 두 RPC를 뺀 값, "
           "전송·기타는 Decode RPC에서 서버 libwebp 디코딩(decode_time 예제, 중앙값, gRPC 없음)을 뺀 값이다. "
           "변환(측정)은 ConversionBenchmark(이미지 할당 포함) 값이다.", "",
           "| 모드 | 크기 | 전체 | GetFeatures | Decode RPC | ↳ 서버 libwebp | ↳ 전송·기타 | 클라이언트 몫 | 변환(측정) | "
           "Decode RPC TCP | 픽셀 바이트 |",
           "|---|---|---|---|---|---|---|---|---|---|---|"]
    any_row = False
    for mode in MODES:
        for size in SIZES:
            total = find(rows, "DecodeBenchmark.decode", "decode", impl="SIDECAR", server="sse2-uds", content="photo",
                         size=size, mode=mode)
            dec = find(rows, "TransportBenchmark.decode", server="sse2-uds", content="photo", size=size, mode=mode)
            feat = find(rows, "TransportBenchmark.getFeatures", server="sse2-uds", content="photo", size=size,
                        mode=mode)
            tcp = find(rows, "TransportBenchmark.decode", server="sse2-tcp", content="photo", size=size, mode=mode)
            conv = find(rows, "ConversionBenchmark.rgbaToArgbImage", size=size)
            if not (dec or feat):
                continue
            any_row = True
            t, d, f = ms(total), ms(dec), ms(feat)
            client = t - d - f if None not in (t, d, f) else None
            w, h = DIMS[size]
            server = decode_time.get(f"{size}_{w}x{h}_{FILE_MODES[mode]}.webp")
            transfer = d - server if None not in (d, server) else None
            out.append(f"| {mode} | {size} | {fmt_ms(t)} | {fmt_ms(f)} | {fmt_ms(d)} | {fmt_ms(server)} | "
                       f"{fmt_ms(transfer)} | {fmt_ms(client)} | {fmt_ms(ms(conv))} | {fmt_ms(ms(tcp))} | {w * h * 4:,} |")
    return out + [""] if any_row else []


def server_decode_section(sse2: dict[str, float], avx2: dict[str, float]) -> list[str]:
    if not sse2:
        return []
    out = ["## 서버 안 libwebp 디코딩 (gRPC 없음, photo, 중앙값 ms)", "",
           "| 모드 | 크기 | 기본(SSE2) | AVX2 | AVX2/SSE2 시간 |", "|---|---|---|---|---|"]
    for mode in MODES:
        for size in SIZES:
            w, h = DIMS[size]
            key = f"{size}_{w}x{h}_{FILE_MODES[mode]}.webp"
            a, b = sse2.get(key), avx2.get(key)
            r = f"{b / a:.2f}" if a and b else "—"
            out.append(f"| {mode} | {size} | {fmt_ms(a)} | {fmt_ms(b)} | {r} |")
    return out + [""]


def encode_section(rows) -> list[str]:
    out = ["## 정지 이미지 인코딩 (photo, ops/s, 판정 대상 아님)", "",
           "| 모드 | 크기 | 사이드카 | darkxanter (JNI) | 사이드카/JNI |", "|---|---|---|---|---|"]
    any_row = False
    for mode in MODES:
        for size in SIZES:
            p = dict(content="photo", size=size, mode=mode)
            s = find(rows, "EncodeBenchmark.encode", impl="SIDECAR", **p)
            d = find(rows, "EncodeBenchmark.encode", impl="DARKXANTER", **p)
            if not (s or d):
                continue
            any_row = True
            out.append(f"| {mode} | {size} | {ops(s)} | {ops(d)} | {fmt_ratio(ratio(s, d))} |")
    return out + [""] if any_row else []


def animation_section(rows) -> list[str]:
    s = find(rows, "AnimationBenchmark.readAllFrames", impl="SIDECAR")
    t = find(rows, "AnimationBenchmark.readAllFrames", impl="TWELVEMONKEYS")
    if not (s or t):
        return []
    return ["## 애니메이션 전체 프레임 읽기 (1024x768 x 12, lossy, ops/s, 참고값)", "",
            "| 사이드카 | TwelveMonkeys | 사이드카/TM |", "|---|---|---|",
            f"| {ops(s)} | {ops(t)} | {fmt_ratio(ratio(s, t))} |", "",
            "사이드카는 합성 캔버스, TwelveMonkeys는 프레임 자체를 돌려준다(결과 의미가 다르다).", ""]


def conversion_section(rows) -> list[str]:
    out = ["## 픽셀 변환 RGBA8 → INT_ARGB (이미지 할당 포함, ms/op)", "", "| 크기 | ms/op | ops/s |", "|---|---|---|"]
    any_row = False
    for size in SIZES:
        c = find(rows, "ConversionBenchmark.rgbaToArgbImage", size=size)
        if c:
            any_row = True
            out.append(f"| {size} | {fmt_ms(ms(c))} | {ops(c)} |")
    return out + [""] if any_row else []


def main() -> None:
    if len(sys.argv) != 2:
        raise SystemExit("usage: summarize.py <results-dir>")
    results = Path(sys.argv[1])
    rows = load(results)
    if not rows:
        raise SystemExit(f"no JMH results in {results}")
    with open(results / "results.csv", "w", newline="", encoding="utf-8") as f:
        keys = sorted({k for r in rows for k in r["params"]})
        w = csv.writer(f, lineterminator="\n")
        w.writerow(["suite", "benchmark", *keys, "score", "error", "unit", "forks", "iterations"])
        for r in rows:
            w.writerow([r["suite"], r["benchmark"], *[r["params"].get(k, "") for k in keys],
                        f"{r['score']:.6g}", f"{r['error']:.6g}", r["unit"], r["forks"], r["iterations"]])
    lines = ["# Phase 4 벤치마크 요약", "", f"결과: `{results.name}` ({len(rows)}개 측정). 환경: `environment.txt`.", ""]
    for section in (decode_section, servers_section, chunk_section):
        lines += section(rows)
    lines += breakdown_section(rows, load_decode_time(results, "sse2"))
    lines += server_decode_section(load_decode_time(results, "sse2"), load_decode_time(results, "avx2"))
    for section in (conversion_section, wasm_interp_section, encode_section, animation_section):
        lines += section(rows)
    (results / "summary.md").write_text("\n".join(lines), encoding="utf-8")
    print(f"summary -> {results / 'summary.md'}")


if __name__ == "__main__":
    main()
