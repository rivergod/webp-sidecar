# 0004. 픽셀 전송 포맷: RGBA8 비-premultiplied, 행 패딩 없음

- 상태: accepted
- 날짜: 2026-10-23
- 결정자: Ilkang Na
- 관련 문서: [ADR-0003](0003-transport.md), [docs/design/grpc-api.md](../design/grpc-api.md)

## 맥락

서버와 클라이언트는 압축 해제된 픽셀을 주고받는다(디코딩 결과, 인코딩 입력, 애니메이션 합성 프레임). 다음을 정해야 한다.

- 채널 구성과 순서 (RGBA / BGRA / ARGB / RGB …)
- premultiplied alpha 여부
- 행 간격(stride)과 패딩

관련 사실 (libwebp v1.6.0 공개 헤더, 확인 2026-10-23):

- 디코더 출력 모드: `MODE_RGB`, `MODE_RGBA`, `MODE_BGR`, `MODE_BGRA`, `MODE_ARGB`, `MODE_RGBA_4444`, `MODE_RGB_565`, premultiplied 계열(`MODE_rgbA`, `MODE_bgrA`, `MODE_Argb`, `MODE_rgbA_4444`), `MODE_YUV`/`MODE_YUVA` — `src/webp/decode.h` 151–162행 [S1]
- `WebPAnimDecoder`는 `MODE_RGBA`, `MODE_BGRA`, `MODE_rgbA`, `MODE_bgrA`만 지원한다 — `src/webp/demux.h` 256–258행 [S2]
- 인코더 입력: `WebPPictureImportRGB/RGBA/RGBX/BGR/BGRA/BGRX` — `src/webp/encode.h` 471–487행 [S3]
- 최대 크기: `WEBP_MAX_DIMENSION 16383` — `src/webp/encode.h` 298행 [S3]

JVM 쪽에서 많이 쓰는 `BufferedImage` 타입은 `TYPE_INT_ARGB`(int 하나에 A,R,G,B, 비-premultiplied), `TYPE_4BYTE_ABGR`(바이트 A,B,G,R), `TYPE_INT_ARGB_PRE`(premultiplied)다. 어떤 와이어 포맷을 고르든 클라이언트는 수신한 청크를 대상 버퍼로 복사해야 하므로, 복사와 채널 재배치를 한 번에 할 수 있다.

## 결정 동인

1. 정확성 — lossless 왕복에서 픽셀이 바뀌지 않아야 한다.
2. 단순함 — 서버와 클라이언트가 처리할 조합 수가 적어야 한다.
3. 전송량과 변환 비용 — UDS에서도 대용량 이미지의 복사·변환은 비용이 크다.
4. 애니메이션 경로와 정지 이미지 경로가 같은 포맷을 쓸 수 있어야 한다.

## 검토한 선택지

1. **RGBA8 고정**, 비-premultiplied, 행 패딩 없음
2. RGBA8 기본 + 다중 포맷(RGB8, BGRA8, ABGR8 등)을 요청 시 선택
3. Java 타입에 맞춘 포맷 고정(예: ABGR8 → `TYPE_4BYTE_ABGR`에 바로 쓰기)
4. premultiplied RGBA8

## 결정

**선택지 1을 택한다.** 와이어 포맷은 RGBA8(채널당 8비트, 바이트 순서 R,G,B,A), 비-premultiplied, sRGB 값 그대로(색 관리는 하지 않음), 행 패딩 없음(stride = width × 4)으로 고정한다.

- proto에는 `PixelFormat` enum을 두되 v1에서는 `PIXEL_FORMAT_RGBA8`만 정의한다. 다른 포맷이 필요해지면 enum 값을 추가해도 기존 클라이언트가 깨지지 않는다.
- stride 필드는 두지 않는다. 바이트 수는 항상 `width × height × 4`이고, 양쪽이 이 값으로 응답 크기를 검증한다.
- 불투명 이미지도 RGBA8로 보낸다(A=255). RGB8 대비 전송량이 33% 많지만, 이 차이가 실제로 문제인지는 Phase 4 벤치마크에서 측정한 뒤 판단한다.
- ICC 프로파일은 픽셀에 적용하지 않고 메타데이터로 따로 전달한다. 색 변환을 클라이언트에서 할지는 Phase 3에서 정한다(TwelveMonkeys는 읽은 뒤 적용한다 — [docs/research/0001](../research/0001-java-webp-landscape.md)).

### 결과

- 긍정: 정지 이미지(`WebPDecodeRGBA*`), 애니메이션(`WebPAnimDecoder`의 `MODE_RGBA`), 인코딩(`WebPPictureImportRGBA`)이 모두 같은 포맷을 서버 쪽 변환 없이 쓴다.
- 긍정: 비-premultiplied라 lossless 왕복이 정확하다. premultiply는 alpha가 작은 픽셀의 색 정보를 잃는다.
- 부정: 클라이언트는 `TYPE_INT_ARGB`나 `TYPE_4BYTE_ABGR`로 옮길 때 채널을 재배치해야 한다. `ComponentColorModel` + 밴드 오프셋 `{0,1,2,3}`으로 RGBA 바이트를 그대로 감싸는 방법도 있지만, 결과가 `TYPE_CUSTOM`이라 Java2D 그리기가 느려질 수 있다(추정).
- 부정: 불투명 이미지에서 25%(4바이트 중 1바이트)가 낭비된다.

### 확인 방법

- Phase 2: 클라이언트 변환(RGBA → `TYPE_INT_ARGB` / `TYPE_4BYTE_ABGR`) 비용을 단위 벤치로 측정한다.
- Phase 4: 전송량과 변환 비용이 전체 처리 시간에서 차지하는 비율을 측정한다. 의미 있는 비율이면 RGB8 또는 ABGR8 추가를 새 ADR로 제안한다.

## 선택지별 장단점

### 1. RGBA8 고정

- 장점: 조합이 하나. libwebp 디코드·애니메이션·인코드 API가 모두 직접 지원한다.
- 단점: 클라이언트 변환 필요. 불투명 이미지의 전송량 낭비.

### 2. 다중 포맷

- 장점: 불투명 이미지는 RGB8로 25% 절감. 클라이언트 타입에 맞춰 변환 생략 가능.
- 단점: 서버·클라이언트·테스트 조합이 포맷 수만큼 늘어난다. `WebPAnimDecoder`는 RGB를 지원하지 않아 애니메이션 경로는 결국 RGBA/BGRA다. 효과가 측정되기 전에 넣는 것은 추측성 기능이다.

### 3. Java 타입 맞춤 고정 (ABGR8)

- 장점: `TYPE_4BYTE_ABGR`의 `DataBufferByte`에 청크를 그대로 복사할 수 있다.
- 단점: libwebp에 ABGR 출력 모드가 없어 서버가 재배치해야 한다. 와이어 포맷이 특정 클라이언트 언어에 묶인다.

### 4. premultiplied RGBA8

- 장점: `TYPE_INT_ARGB_PRE` 합성에 유리하다.
- 단점: 정보 손실로 lossless 왕복이 깨진다. 인코더 입력(`WebPPictureImportRGBA`)은 비-premultiplied를 받는다.

## 참고

- [S1] libwebp v1.6.0 `src/webp/decode.h` — https://github.com/webmproject/libwebp/blob/v1.6.0/src/webp/decode.h (태그 `v1.6.0` → 커밋 `4fa21912338357f89e4fd51cf2368325b59e9bd9`, 확인 2026-10-23)
- [S2] libwebp v1.6.0 `src/webp/demux.h` — https://github.com/webmproject/libwebp/blob/v1.6.0/src/webp/demux.h (확인 2026-10-23)
- [S3] libwebp v1.6.0 `src/webp/encode.h` — https://github.com/webmproject/libwebp/blob/v1.6.0/src/webp/encode.h (확인 2026-10-23)
