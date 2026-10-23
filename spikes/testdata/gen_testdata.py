#!/usr/bin/env python3
"""스파이크용 아주 작은 입력을 표준 라이브러리만으로 만든다 (버릴 코드).

사용법: python3 -I gen_testdata.py <출력 디렉터리>
생성물:
- still.png(4x3), frame1.png~frame3.png(4x3, 프레임마다 다른 색). cwebp/img2webp 입력용.
  still.png 의 (0,0) 픽셀은 RGBA = (0x12, 0x34, 0x56, 0x78) 이다.
- bomb_16384.webp(41 B): VP8L 헤더만 16384x16384로 선언하고 픽셀 데이터는 없는 파일.
  WebPGetFeatures 상한 검사가 디코딩 전에 거부하는지 보는 용도다.
"""
import struct
import sys
import zlib
from pathlib import Path

W, H = 4, 3


def png_bytes(pixels):
    """pixels: H x W 리스트, 원소는 (r, g, b, a)."""
    raw = b"".join(
        b"\x00" + b"".join(bytes(p) for p in row) for row in pixels
    )

    def chunk(tag, data):
        body = tag + data
        return struct.pack(">I", len(data)) + body + struct.pack(">I", zlib.crc32(body))

    ihdr = struct.pack(">IIBBBBB", W, H, 8, 6, 0, 0, 0)  # 8bit RGBA
    return (
        b"\x89PNG\r\n\x1a\n"
        + chunk(b"IHDR", ihdr)
        + chunk(b"IDAT", zlib.compress(raw, 9))
        + chunk(b"IEND", b"")
    )


def bomb_webp(w=16384, h=16384):
    """VP8L 헤더(서명 0x2F + 14비트 폭-1 + 14비트 높이-1 + 알파 1비트 + 버전 3비트)만 가진 파일."""
    bits = (w - 1) | ((h - 1) << 14) | (1 << 28)
    payload = bytes([0x2F]) + struct.pack("<I", bits) + b"\x00" * 16
    chunk = b"VP8L" + struct.pack("<I", len(payload)) + payload
    riff = b"WEBP" + chunk
    return b"RIFF" + struct.pack("<I", len(riff)) + riff


def main():
    out = Path(sys.argv[1])
    out.mkdir(parents=True, exist_ok=True)
    still = [[(0x12 + 16 * x, 0x34 + 16 * y, 0x56, 0x78 if (x, y) == (0, 0) else 0xFF)
              for x in range(W)] for y in range(H)]
    (out / "still.png").write_bytes(png_bytes(still))
    colors = [(255, 0, 0, 255), (0, 255, 0, 255), (0, 0, 255, 128)]
    for i, c in enumerate(colors, start=1):
        frame = [[c for _ in range(W)] for _ in range(H)]
        (out / f"frame{i}.png").write_bytes(png_bytes(frame))
    (out / "bomb_16384.webp").write_bytes(bomb_webp())


if __name__ == "__main__":
    main()
