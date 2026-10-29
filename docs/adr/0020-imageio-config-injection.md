# 0020. ImageIO 플러그인 설정을 코드로 넘기는 방법

- 상태: proposed
- 날짜: 2026-10-29
- 결정자: -
- 관련 문서: [ADR-0013](0013-client-package-and-imageio-mapping.md)(클라이언트 패키지와 ImageIO 매핑), [docs/design/grpc-api.md](../design/grpc-api.md) §5(클라이언트 설정), [docs/roadmap.md](../roadmap.md) 5-3

## 맥락

ImageIO는 `META-INF/services`(모듈 경로에서는 `module-info`의 `provides`)로 SPI를 찾아 **인자 없는 생성자**로 만든다. 그래서 지금 플러그인 설정은 JVM 전역 값뿐이다: 시스템 프로퍼티 → 환경변수 → 기본값(엔드포인트, 데드라인, 클라이언트 픽셀 상한, grpc-api.md §5).

- 리더·writer를 만들 때마다 다시 읽으므로 실행 중 `System.setProperty`로 바꿀 수는 있다. 하지만 문자열 기반이고, JVM 하나에 값이 하나뿐이다.
- 결정자가 "ImageIO에 등록할 때 설정을 함께 넘길 수 있는가"를 물었다(2026-10-29). 등록 단위로 넘길 방법이 없다.
- 호출 단위 옵션을 넘길 공개 타입도 없다. 예: 이 읽기만 데드라인 5초, 인코더 method 6. `WebPWriteParam`은 package-private이라 사용자는 ImageIO 표준 항목(손실/무손실, 품질)만 바꿀 수 있다.

ImageIO가 허용하는 확장 지점은 세 가지다.

| 지점 | 범위 | 비고 |
|---|---|---|
| SPI 수동 등록: `IIORegistry.getDefaultInstance().registerServiceProvider(spi)` | 등록 단위(JVM 안 `ImageIO.read` 전체) | 같은 클래스의 SPI를 등록하면 기존 것을 대체하는 것으로 알지만, 구현할 때 확인한다(미확인) |
| `ImageReaderSpi.createReaderInstance(Object extension)` | 리더 하나 | `ImageIO.read`는 `null`로 부르므로 SPI를 직접 다루는 코드에서만 효과가 있다 |
| `ImageReadParam` / `ImageWriteParam` 하위 클래스 | 읽기·쓰기 한 번 | `reader.getDefaultReadParam()`으로 얻어 캐스팅하거나 `instanceof`로 확인한다 |

## 결정 동인

1. 기존 방식(시스템 프로퍼티·환경변수)은 그대로 동작해야 한다. 컨테이너와 Spring Boot에서 가장 쉬운 방법이다.
2. 타입이 있는 공개 API로, 등록 단위와 호출 단위를 모두 지원해야 한다.
3. 공개 API를 최소로 늘린다(ADR-0013: 공개 패키지는 `net.rivergod.webp.imageio` 하나).

## 검토한 선택지

1. **공개 설정 타입 + 세 확장 지점 모두**: 불변 설정 타입 하나를 SPI 생성자와 `createReaderInstance(extension)`이 받는다. 호출 단위 옵션은 공개 Param 하위 클래스로 넘긴다
2. 시스템 프로퍼티·환경변수만 유지하고, 프로그램에서 바꾸는 방법을 문서화한다
3. 정적 전역 설정 메서드(`WebPImageIO.configure(config)`) 하나만 추가한다
4. 호출 단위 Param만 추가한다

## 결정

**선택지 1을 추천한다.**

- **설정 타입**: 공개 불변 타입 `WebPClientConfig`를 둔다. Java 17 record와 빌더다.
  - 항목: 엔드포인트, 데드라인(ms), 클라이언트 픽셀 상한. 지금 `ClientConfig`의 항목과 같다.
  - `fromEnvironment()`는 지금 규칙(시스템 프로퍼티 → 환경변수 → 기본값)을 그대로 따른다.
- **적용 순서**: 코드로 준 설정 → 시스템 프로퍼티 → 환경변수 → 기본값. 코드로 준 설정에서 비운 항목은 다음 단계 값을 쓴다.
- **등록 단위**:
  - `WebPImageReaderSpi(WebPClientConfig)`와 `WebPImageWriterSpi(WebPClientConfig)` 공개 생성자를 추가한다.
  - 인자 없는 생성자(ServiceLoader가 쓰는)는 `fromEnvironment()`와 같다.
  - 사용 예와 기존 등록 대체 여부를 client-jvm README에 적는다.
- **리더 단위**: `createReaderInstance(extension)`과 `createWriterInstance(extension)`이 `WebPClientConfig`를 받으면 그 설정을 쓴다. 다른 타입이나 `null`이면 SPI의 설정을 쓴다.
- **호출 단위**:
  - `WebPReadParam`(공개)을 추가한다: 데드라인, 픽셀 상한.
  - `WebPWriteParam`을 공개한다: 표준 항목에 더해 method(0–6)와 lossless `exact`.
  - 두 Param 모두 `getDefaultReadParam()`과 `getDefaultWriteParam()`이 돌려주는 타입이다.
- **채널 공유**: 같은 엔드포인트의 gRPC 채널은 지금처럼 공유한다(설정 객체마다 채널을 만들지 않는다).

### 결과

- 긍정: 기존 사용법은 그대로이고, 프로그램에서 타입 있는 설정을 등록·리더·호출 단위로 줄 수 있다. 서버를 여러 개 쓰는 앱(예: 테넌트별 서버)도 지원한다.
- 부정: 공개 타입이 셋 늘어난다(설정, ReadParam, 공개로 바뀌는 WriteParam). 공개 API 문서와 호환 약속이 늘어난다.
- 부정: 수동 등록과 자동 등록이 섞이면 어떤 설정이 쓰이는지 헷갈릴 수 있다. README에 순서를 적고, 등록을 확인하는 테스트를 둔다.

### 확인 방법

- 단위 테스트: 적용 순서(코드 → 프로퍼티 → 환경변수 → 기본값), `createReaderInstance(config)`, Param 값이 Decode/Encode 옵션으로 가는지.
- 등록 테스트: 설정을 준 SPI를 수동 등록하면 `ImageIO.read`가 그 엔드포인트를 쓴다. 클래스패스와 모듈 경로(ModuleLayer) 둘 다 확인한다.
- 통합 테스트: 서버 두 개(UDS, TCP)를 띄우고 리더 단위 설정으로 각각 읽는다.

## 선택지별 장단점

### 1. 공개 설정 타입 + 세 지점 (추천)

- 장점: ImageIO가 허용하는 모든 범위를 타입 있는 API로 덮는다.
- 단점: 공개 API가 가장 많이 늘어난다.

### 2. 지금대로 + 문서화

- 장점: 공개 API가 늘지 않는다.
- 단점: 문자열 기반이고 JVM에 값이 하나뿐이다. 호출 단위 옵션을 줄 수 없다.

### 3. 정적 전역 설정 메서드

- 장점: 단순하다(프레임워크 시작 시 한 번 부른다).
- 단점: 전역 상태라 시스템 프로퍼티와 사실상 같다. 리더·호출 단위를 지원하지 못한다.

### 4. 호출 단위 Param만

- 장점: 공개 API가 작다.
- 단점: 엔드포인트처럼 연결 단위인 설정은 Param에 맞지 않는다(호출마다 채널을 고르는 셈이다).

## 참고

- ImageIO SPI 확장 지점: `javax.imageio.spi.ImageReaderSpi#createReaderInstance(Object)`, `javax.imageio.spi.IIORegistry`, `javax.imageio.ImageReadParam`(JDK 17 API 문서)
