# 0014. 애니메이션·메타데이터 매핑: 순차 읽기, IIOMetadata 형식, ICC, 애니메이션 쓰기

- 상태: accepted
- 날짜: 2026-10-23
- 결정자: Ilkang Na
- 관련 문서: [docs/design/grpc-api.md](../design/grpc-api.md) §2.4, [docs/research/0001-java-webp-landscape.md](../research/0001-java-webp-landscape.md), [ADR-0013](0013-client-package-and-imageio-mapping.md)

## 맥락

Phase 3에서 애니메이션과 ICC/EXIF/XMP를 ImageIO에 연결한다. 정할 것은 네 가지다.

1. 순차 프레임 읽기를 어떻게 효율적으로 할까(`read(i)`를 차례로 부르면 서버가 매번 0번부터 합성한다)
2. `IIOMetadata`를 어떤 형식으로 노출할까. TwelveMonkeys는 표준 포맷(`javax_imageio_1.0`)의 압축·chroma 정보만 노출하고 native 포맷이 없다([research/0001](../research/0001-java-webp-landscape.md)).
3. ICC 프로파일을 픽셀에 적용할까
4. 애니메이션 쓰기의 입력 규칙

## 결정 동인

1. 순차 읽기가 O(n²)이 되지 않아야 한다.
2. 메타데이터를 잃지 않고 왕복(읽기 → 쓰기)할 수 있어야 한다.
3. 다른 WebP 리더와 바꿔 끼우기 쉬워야 한다(표준 포맷 호환).
4. 단순함.

## 결정

### 순차 읽기

- 리더는 입력 하나에 `DecodeAnimation` 스트림을 하나 열어 둔다. `read(i)`의 `i`가 바로 앞에 읽은 프레임 + 1이면(처음에는 0) 그 스트림에서 다음 프레임을 받는다.
- 그 밖의 `i`는 스트림을 취소하고 `Decode(frame_index = i)`로 한 프레임만 받는다.
- 열어 둔 스트림은 서버의 동시 처리 슬롯(ADR-0012)을 차지한다. 그래서 리더는 `setInput`, `reset`, `dispose`에서 스트림을 바로 취소한다. 스트림이 데드라인으로 끊기면 다음 `read(i)`는 `Decode(frame_index)`로 이어 간다.

### IIOMetadata 형식

- native 포맷 이름: `net_rivergod_webp_imageio_1.0`
- 스트림 메타데이터(`getStreamMetadata`): 파일 단위 정보 — ICC, EXIF, XMP, 애니메이션 전체 정보(반복 횟수, 배경색).
- 이미지 메타데이터(`getImageMetadata(i)`): 프레임 단위 정보 — 표준 포맷 노드와 native 포맷의 프레임 노드(위치, 크기, 표시 시간, blend, dispose).
- ICC/EXIF/XMP는 **청크 페이로드 원본 바이트 그대로** 담는다. EXIF·XMP를 해석하지 않는다.
- 표준 포맷(`javax_imageio_1.0`)은 TwelveMonkeys와 같은 노드를 낸다.
- 노드 이름과 속성의 세부 구조는 Phase 3 설계 문서에서 정한다.

### ICC

- 픽셀에 ICC 변환을 적용하지 않는다. 디코딩한 값을 그대로 돌려주고, 프로파일은 스트림 메타데이터로만 제공한다(서버도 적용하지 않는다 — ADR-0004).

### 애니메이션 쓰기

- `canWriteSequence()`는 true다. 시퀀스의 모든 프레임은 같은 크기(캔버스 크기)여야 하고, 다르면 `IIOException`을 던진다.
- 프레임 표시 시간은 프레임별 이미지 메타데이터에서 읽는다. 없으면 **100 ms**다.
- 반복 횟수는 스트림 메타데이터에서 읽는다. 없으면 **0(무한)**이다. 배경색이 없으면 libwebp 기본값을 쓴다.

### 결과

- 긍정: 순차 읽기가 O(n)이다. 임의 접근도 동작한다.
- 긍정: 메타데이터가 원본 바이트로 보존돼 왕복에서 바뀌지 않는다.
- 부정: 순차 읽기 스트림이 서버 슬롯을 차지하므로, 리더를 닫지 않고 방치하면 데드라인까지 다른 요청이 기다릴 수 있다.
- 부정: EXIF/XMP 해석(예: 방향 태그)은 사용자가 직접 해야 한다.
- 부정: 프레임 크기가 다른 시퀀스(예: GIF 변환)는 사용자가 캔버스 크기로 맞춰야 한다.
- 부정: ICC가 있는 이미지의 색은 사용자가 변환하지 않으면 sRGB로 해석된다.

### 확인 방법

- Phase 3: `read(0..n-1)` 순차 읽기에서 `DecodeAnimation` 한 번만 호출됨을 통합 테스트로 확인한다.
- Phase 3: 메타데이터 왕복(읽기 → 쓰기 → 읽기)에서 ICC/EXIF/XMP 바이트와 프레임 정보가 같다.

## 검토한 선택지

- 순차 읽기: **스트림 재사용 + 임의 접근 시 단일 프레임**(채택) / 항상 `Decode(frame_index)` / 전체 프레임을 한 번에 받아 클라이언트 캐시(메모리 과다)
- 메타데이터: **원본 바이트 native 포맷**(채택) / EXIF·XMP를 해석한 트리 / 표준 포맷만(TwelveMonkeys와 같음, 메타데이터 손실)
- ICC: **적용 안 함**(채택) / 읽을 때 sRGB로 변환(TwelveMonkeys 방식, RGB 프로파일만) / `ICC_ColorSpace`를 단 `BufferedImage`
- 애니메이션 쓰기: **같은 크기 강제**(채택) / 메타데이터의 위치로 합성
