# ImageIO 메타데이터와 애니메이션 매핑

- 상태: 구현됨(Phase 3). 결정: [ADR-0014](../adr/0014-animation-and-metadata-mapping.md), [ADR-0013](../adr/0013-client-package-and-imageio-mapping.md)
- 코드: `client-jvm/src/main/java/net/rivergod/webp/sidecar/` (`WebPStreamMetadata*`, `WebPImageMetadata*`, `AnimationStream`, `WebPImageReader`, `WebPImageWriter`)

## 1. 포맷 이름

native 포맷 이름은 스트림과 이미지 메타데이터 모두 `net_rivergod_webp_sidecar_1.0`이다(`WebPStreamMetadataFormat.NATIVE_FORMAT_NAME`). 표준 포맷(`javax_imageio_1.0`)은 이미지 메타데이터만 지원한다.

## 2. 스트림 메타데이터 (파일 단위)

```
<net_rivergod_webp_sidecar_1.0>
  <ICCProfile/>   userObject = byte[]  ICCP 청크 페이로드 원본 (RFC 9649 §2.7.1.4)
  <EXIF/>         userObject = byte[]  EXIF 청크 페이로드 원본 (§2.7.1.5, "Exif\0\0" 접두어도 그대로)
  <XMP/>          userObject = byte[]  XMP 청크 페이로드 원본 (§2.7.1.5)
  <Animation loopCount="0" backgroundColor="0xAARRGGBB"/>   애니메이션일 때만 (§2.7.1.1)
</net_rivergod_webp_sidecar_1.0>
```

- 읽기: `getStreamMetadata()`. 리더를 `ignoreMetadata=true`로 설정하면 null이고, 서버에 메타데이터를 요청하지 않는다.
- 쓰기: `write(streamMetadata, image, param)`과 `prepareWriteSequence(streamMetadata)`가 이 값을 쓴다. 다른 플러그인의 메타데이터는 native 포맷을 지원할 때만 읽고, 아니면 무시한다.
- 시퀀스 쓰기에서 `Animation`이 없으면 반복 0(무한), 배경색은 libwebp 기본값 `0xFFFFFFFF`(흰색 불투명, libwebp `src/mux/anim_encode.c`)를 쓴다.
- 페이로드는 해석하지 않는다. EXIF 방향 같은 해석은 사용자가 한다. ICC는 픽셀에 적용하지 않는다(ADR-0004, ADR-0014).

## 3. 이미지 메타데이터 (프레임 단위)

### native

```
<net_rivergod_webp_sidecar_1.0>
  <Frame xOffset="…" yOffset="…" width="…" height="…" durationMs="100"
         blend="alphaBlend|noBlend" dispose="none|background" hasAlpha="TRUE|FALSE" compression="lossy|lossless"/>
</net_rivergod_webp_sidecar_1.0>
```

- 읽기: `getImageMetadata(i)`는 i번째 ANMF의 값을 돌려준다. 좌표와 크기는 프레임 사각형이고, `read(i)`가 돌려주는 이미지는 그 프레임까지 합성한 **캔버스 전체**다. 정지 이미지는 캔버스 전체를 덮는 프레임 하나(`durationMs=0`)다.
- 쓰기: `writeToSequence`는 `durationMs`만 쓴다(없으면 100 ms). 나머지 속성(오프셋, blend, dispose)은 libwebp `WebPAnimEncoder`가 정한다.

### 표준 (`javax_imageio_1.0`)

TwelveMonkeys imageio-webp(`StandardImageMetadataSupport`)와 같은 노드를 낸다([research/0001](../research/0001-java-webp-landscape.md)).

| 노드 | 값 |
|---|---|
| `Chroma` | `ColorSpaceType name="RGB"`, `NumChannels` 3(alpha 없음) / 4, `BlackIsZero TRUE` |
| `Compression` | `CompressionTypeName` VP8 / VP8L, `Lossless` FALSE / TRUE — 그 프레임의 비트스트림 기준 |
| `Data` | `PlanarConfiguration PixelInterleaved`, `SampleFormat UnsignedIntegral`, `BitsPerSample` "8 8 8" / "8 8 8 8" |
| `Dimension` | `PixelAspectRatio 1.0`, `ImageOrientation Normal` |
| `Document` | `FormatVersion 1.0` |
| `Transparency` | `Alpha` none / nonpremultiplied |

"alpha 있음"은 `read(i)`가 돌려주는 이미지 기준이다. 정지 이미지는 비트스트림에 알파가 있을 때, 애니메이션 프레임은 **항상**이다(투명으로 초기화한 캔버스에 합성한 ARGB를 돌려주므로 — §4). native `Frame/hasAlpha`는 프레임 비트스트림의 알파를 그대로 적는다.

#### TwelveMonkeys와 값이 다른 경우

Phase 4 벤치 모듈에서 실제 출력 트리를 나란히 비교했다(벤치 세트와 `samples/generated`의 정지·메타데이터·애니메이션 파일). 노드 구성은 모두 같고, 정지 이미지 중 단순 형식(VP8, VP8L, VP8X+VP8 / ALPH)은 값까지 같다. 다른 경우는 다음 둘이다.

| 경우 | 이 플러그인 | TwelveMonkeys 3.15.3 | 판단 |
|---|---|---|---|
| VP8X 컨테이너 안의 VP8L(정지 이미지 + ICC/EXIF/XMP, lossless 애니메이션 프레임) | `VP8L` / `Lossless TRUE` | `VP8` / `Lossless FALSE` | 파일은 lossless다. TwelveMonkeys 값을 따르지 않는다 |
| 애니메이션 프레임의 알파 | 항상 있음(반환 이미지가 ARGB 캔버스) | VP8X 헤더 알파 플래그를 따른다 | 플래그가 없는 애니메이션에서만 다르다. 메타데이터가 반환 이미지와 맞아야 하므로 이 플러그인 규칙을 유지한다 |

## 4. 애니메이션 읽기

- `getNumImages(true)`: 프레임 수(GetFeatures).
- `read(i)`: i번째 프레임까지 합성한 캔버스(TYPE_INT_ARGB). 합성은 libwebp `WebPAnimDecoder` 동작을 따른다(ANIM 배경색 무시, 투명으로 초기화).
- 순차 읽기(ADR-0014): `read(0)`에서 DecodeAnimation 스트림을 열고, 다음 `read(i)`가 바로 다음 프레임이면 그 스트림에서 받는다. 그 밖의 `i`는 스트림을 닫고 `Decode(frame_index = i)`로 받는다. 스트림이 실패하면(데드라인 등) 헤더를 받기 전이었다면 같은 프레임을 Decode로 다시 받는다.
- 스트림은 서버 동시 처리 슬롯을 차지하므로 `setInput`, `reset`, `dispose`에서 바로 취소한다. `ImageIO.read`는 끝나면 리더를 dispose한다.
- 수신은 수동 흐름 제어(메시지 하나 소비 → 다음 하나 요청)라 리더가 읽지 않으면 서버도 보내지 않는다.

## 5. 애니메이션 쓰기

- `canWriteSequence()`는 true다. `prepareWriteSequence` → `writeToSequence` × N → `endWriteSequence`.
- 첫 프레임의 크기가 캔버스 크기다. 다른 크기의 프레임은 `IIOException`으로 거부한다.
- 압축 설정(lossy/lossless, 품질)은 첫 `writeToSequence`의 param을 모든 프레임에 쓴다.
- 프레임은 받는 대로 서버로 보낸다(전체를 모으지 않는다). 결과 파일은 `endWriteSequence`에서 출력 스트림에 쓴다.
