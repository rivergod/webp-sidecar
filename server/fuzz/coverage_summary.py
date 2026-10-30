"""llvm-cov export 요약(JSON)을 libwebp 디렉터리와 서버 파일별 표(Markdown)로 줄인다(server/fuzz/coverage.sh).

사용: python3 -I coverage_summary.py <export.json> "<대상 목록>"
줄·함수 커버리지는 llvm-cov가 센 값을 디렉터리별로 더한다. libwebp 경로는 libwebp-sys가 담은 vendor/src 아래다.
"""

import json
import sys
from collections import defaultdict


def main() -> None:
    path, targets = sys.argv[1], sys.argv[2]
    with open(path, encoding="utf-8") as f:
        data = json.load(f)
    groups = defaultdict(lambda: [0, 0, 0, 0])  # 줄 수, 실행 줄, 함수 수, 실행 함수
    for entry in data["data"]:
        for file in entry["files"]:
            name = file["filename"]
            if "/vendor/src/" in name and "libwebp-sys" in name:
                rel = name.split("/vendor/src/", 1)[1]
                key = "libwebp src/" + rel.split("/", 1)[0]
            elif "/vendor/sharpyuv/" in name:
                key = "libwebp sharpyuv"
            elif "/server/src/" in name:
                key = "server src/" + name.split("/server/src/", 1)[1]
            else:
                continue
            s = file["summary"]
            g = groups[key]
            g[0] += s["lines"]["count"]
            g[1] += s["lines"]["covered"]
            g[2] += s["functions"]["count"]
            g[3] += s["functions"]["covered"]
    print(f"대상: {targets}\n")
    print("| 범위 | 줄 | 실행한 줄 | 줄 커버리지 | 함수 | 실행한 함수 | 함수 커버리지 |")
    print("|---|---|---|---|---|---|---|")
    for key in sorted(groups):
        lines, lc, funcs, fc = groups[key]
        lp = 100.0 * lc / lines if lines else 0.0
        fp = 100.0 * fc / funcs if funcs else 0.0
        print(f"| {key} | {lines} | {lc} | {lp:.1f}% | {funcs} | {fc} | {fp:.1f}% |")


if __name__ == "__main__":
    main()
