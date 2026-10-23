# 0013. 클라이언트 패키지와 ImageIO 매핑: 패키지·모듈 이름, 디코딩 결과 타입, 쓰기 파라미터

- 상태: accepted
- 날짜: 2026-10-23
- 결정자: Ilkang Na
- 관련 문서: [ADR-0004](0004-pixel-transfer-format.md), [ADR-0005](0005-jdk-language-build.md), [ADR-0010](0010-maven-coordinates.md)

## 맥락

ADR-0010은 Maven 좌표(`net.rivergod:webp-imageio-external-server-proto`)를 정했고 Java 패키지와 JPMS 모듈 이름은 남겨 두었다. Phase 2에서 정지 이미지 읽기·쓰기를 구현하려면 디코딩 결과의 `BufferedImage` 타입과 `ImageWriteParam` 매핑도 정해야 한다.

## 결정 동인

1. 사용자는 `ImageIO.read/write`만으로 쓸 수 있어야 하고, 내부 구현(생성된 gRPC 코드)에 의존하지 않아야 한다.
2. 표준 `BufferedImage` 타입을 돌려줘 Java2D 그리기 성능과 호환성을 확보한다.
3. 요청받지 않은 설정을 노출하지 않는다.

## 검토한 선택지

- 디코딩 결과: **alpha 유무에 따라 `TYPE_INT_ARGB` / `TYPE_INT_RGB`** / `TYPE_4BYTE_ABGR` / `TYPE_3BYTE_BGR` / RGBA 바이트를 그대로 감싼 `TYPE_CUSTOM`
- 쓰기 파라미터: **표준 `ImageWriteParam`의 압축 타입·품질만** / 전용 `WebPWriteParam`으로 libwebp 옵션 노출

## 결정

| 항목 | 결정 |
|---|---|
| 기본 패키지 | `net.rivergod.webp.imageio` |
| JPMS 모듈 이름 | `net.rivergod.webp.imageio` |
| 생성된 proto/gRPC 코드 | `net.rivergod.webp.imageio.proto.v1`. `module-info`에서 export하지 않는다. buf managed mode의 `java_package`로 지정한다 |
| 공개 패키지 | `net.rivergod.webp.imageio` 하나. 사용자는 ImageIO API로만 쓴다 |
| 디코딩 결과 타입 | alpha가 있으면 `TYPE_INT_ARGB`, 없으면 `TYPE_INT_RGB`. Phase 2에서 RGBA8 → 각 타입 변환 비용을 측정해 확정한다(ADR-0004 확인 방법) |
| 압축 타입 | `ImageWriteParam`의 압축 타입은 `"Lossy"`, `"Lossless"` 두 개다. 명시하지 않으면 lossy와 libwebp 기본 품질을 쓴다 |
| 품질 | `compressionQuality` 0.0–1.0을 libwebp `quality` 0–100으로 바꾼다(× 100). lossless에서는 libwebp 의미대로 압축 노력이다 |
| 그 밖의 옵션 | 노출하지 않는다(`method` 등은 서버 기본값). 필요해지면 새 ADR로 추가한다 |

### 결과

- 긍정: 생성된 코드가 사용자 API에 새지 않는다. 내부 구현을 바꿔도 사용자 코드가 깨지지 않는다.
- 긍정: 표준 타입이라 다른 ImageIO 리더(TwelveMonkeys 등)를 쓰던 코드와 바꿔 끼우기 쉽다.
- 부정: RGBA8 → int 픽셀 변환 비용이 생긴다(측정 대상).
- 부정: libwebp의 세부 인코딩 옵션을 사용자가 조정할 수 없다.

### 확인 방법

- Phase 2: module path와 classpath 양쪽에서 SPI가 등록되고, `net.rivergod.webp.imageio.proto.v1`이 export되지 않는다.
- Phase 2: 변환 비용 측정 결과를 기록하고 타입 확정 여부를 적는다.
- Phase 2: `ImageWriteParam`의 압축 타입·품질 조합별 인코딩 테스트가 통과한다.

## 선택지별 장단점

### 디코딩 결과 타입

- `TYPE_INT_ARGB` / `TYPE_INT_RGB`(채택): 가장 흔한 타입이고 Java2D 경로가 빠르다(추정). 채널 재배치가 필요하다.
- `TYPE_4BYTE_ABGR` / `TYPE_3BYTE_BGR`: 바이트 단위 복사지만 채널 순서를 바꿔야 하는 것은 같다.
- `TYPE_CUSTOM`(RGBA 그대로): 변환이 없지만 Java2D 그리기가 느려질 수 있다(추정).

### 쓰기 파라미터

- 표준 파라미터만(채택): 다른 ImageIO writer와 같은 방식으로 쓴다.
- 전용 파라미터: 세밀한 조정이 가능하지만 요청받지 않은 설정이다.
