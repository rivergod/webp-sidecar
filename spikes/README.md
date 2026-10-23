# spikes/

버릴 코드. ADR-0002 결정 후 삭제한다. 결과는 docs/research/0003에 기록.

이 디렉터리의 코드는 서버 구현 언어(Rust vs Go)를 고르기 위한 실현 가능성 확인용이다. 제품 코드로 옮기지 않는다. 오류 처리, 안전한 래퍼, 테스트는 일부러 생략했다.

## 구성

| 경로 | 내용 |
|---|---|
| `rust-webp-decode/` | `libwebp-sys` 0.14.4(libwebp 1.6.0 벤더 소스를 `cc`로 정적 빌드)로 libwebp를 직접 호출 |
| `go-webp-decode/` | 외부 Go 바인딩 없이 cgo로 libwebp 1.6.0(공식 tarball을 소스에서 정적 빌드)을 직접 호출 |
| `testdata/` | 아주 작은 입력 파일과 생성 스크립트 |

두 스파이크는 같은 일을 한다.

1. 링크된 libwebp 버전(decoder/encoder/demux/mux)을 출력한다.
2. `WebPGetFeatures`로 크기를 먼저 읽고 픽셀 수 상한을 검사한 뒤 `WebPDecodeRGBA`로 디코딩한다. 크기, 알파 여부, 첫 픽셀 RGBA를 출력한다.
3. `WebPMuxCreate` + `WebPMuxGetChunk`로 ICCP/EXIF/XMP 청크 크기를 출력한다.
4. `WebPAnimDecoder`로 애니메이션의 프레임 수와 합성된 각 프레임의 첫 픽셀을 출력한다.
5. (Go만) cgo 포인터 규칙 위반 시 런타임 검사가 패닉을 내는지, `runtime.Pinner`로 해결되는지 보여 준다.

상한 검사만 따로 보려면 `bomb_16384.webp`를 첫 인자로 준다(예: `docker run ... spike-rust-webp /data/bomb_16384.webp`). 종료 코드 1과 `too large` 오류가 나와야 한다.

## 실행 방법

로컬에 Rust/Go 툴체인이 없어도 된다. Docker만 있으면 된다.

```sh
# 저장소 루트에서 실행한다.
docker build -t spike-rust-webp spikes/rust-webp-decode
docker build -t spike-go-webp spikes/go-webp-decode

# 하드닝 옵션을 준 상태로 실행한다(입력은 읽기 전용 마운트).
docker run --rm --read-only --network none --cap-drop ALL \
  --security-opt no-new-privileges --memory 256m --cpus 1 \
  -v "$PWD/spikes/testdata:/data:ro" spike-rust-webp
```

선택 타깃:

- `rust-webp-decode`: `--target musl` (musl 정적 바이너리 + `scratch`)
- `go-webp-decode`: `--target static` (glibc 완전 정적 + `distroless/static`)
- 두 스파이크 모두 `Dockerfile.cross-arm64`: amd64 호스트에서 arm64 바이너리 크로스 빌드만 확인한다(실행은 검증하지 않음).

## 테스트 입력 (`testdata/`)

`gen_testdata.py`(파이썬 표준 라이브러리만 사용)로 4x3 RGBA PNG와 `bomb_16384.webp`를 만든 뒤, PNG는 로컬 libwebp 1.5.0 도구로 변환했다. 모두 이 저장소에서 직접 만든 파일이며 제3자 이미지는 없다. ICC/EXIF/XMP 내용은 형식 검증을 하지 않는 더미 바이트다.

```sh
python3 -I spikes/testdata/gen_testdata.py <작업 디렉터리>
cwebp -quiet -lossless -exact still.png -o still_plain.webp
printf 'DUMMY-ICC-NOT-A-REAL-PROFILE' > dummy.icc
printf 'Exif\0\0DUMMY-EXIF' > dummy.exif
printf '<x:xmpmeta xmlns:x="adobe:ns:meta/"/>' > dummy.xmp
webpmux -set icc dummy.icc still_plain.webp -o s1.webp
webpmux -set exif dummy.exif s1.webp -o s2.webp
webpmux -set xmp dummy.xmp s2.webp -o still.webp
img2webp -lossless -d 100 frame1.png frame2.png frame3.png -o anim.webp
```

| 파일 | 크기 | sha256 | 내용 |
|---|---|---|---|
| `still.webp` | 198 B | `9f22c986861939213f503f7a0a8e44bbe9638ba734cbe56229ff8348baf5e5f9` | 4x3 무손실 RGBA, (0,0) = (0x12,0x34,0x56,0x78), ICCP 28 B / EXIF 16 B / XMP 37 B |
| `anim.webp` | 188 B | `5cb9dc7e669fbbfc4d0d5d4d19cb185ddc93dd4983b1e823b63b209fe3d04c00` | 4x3, 3프레임(빨강, 초록, 반투명 파랑), 각 100 ms |
| `bomb_16384.webp` | 41 B | `feaa8b2aee3356ad27d4f876762f8d82d4dd56f23457dbb081ccfb1a345dbd27` | VP8L 헤더만 16384x16384로 선언(픽셀 데이터 없음). 상한 검사 확인용 |

결과 기록은 `docs/research/0003-server-language-rust-vs-go.md`의 "스파이크 결과"를 본다.
