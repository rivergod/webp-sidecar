# 0020. ImageIO 플러그인 설정을 코드로 넘기는 방법

- 상태: accepted. 설정 키 이름과 기본 엔드포인트만 superseded by [0022](0022-project-name-webp-sidecar.md)(2026-11-03)
- 날짜: 2026-10-29
- 결정자: Ilkang Na
- 관련 문서: [ADR-0013](0013-client-package-and-imageio-mapping.md)(클라이언트 패키지와 ImageIO 매핑), [ADR-0017](0017-output-volume-limits.md)(대기 상한, "server busy"), [ADR-0016](0016-memory-budget-admission.md)(메모리 예산), [docs/design/grpc-api.md](../design/grpc-api.md) §5(클라이언트 설정), [docs/roadmap.md](../roadmap.md) 5-3
- 개정: 2026-10-30 — 결정자 검토를 반영했다.
  - lossless `exact`를 뺐다. proto `EncodeParams`에 없는 항목이라 proto와 서버를 함께 바꿔야 한다.
  - ADR-0017이 넘긴 "server busy" 자동 재시도를 정했다(기본 끔).
  - 미확인이던 "같은 클래스 SPI 등록 시 대체"를 JDK 소스로 확인했다. 그래서 등록 단위 설정은 JVM에 하나다.
  - 설정 타입을 record에서 빌더로 만드는 불변 클래스로 바꿨다. 비운 항목을 나타내야 하기 때문이다.
  - `fromEnvironment()`를 뺐다. 비운 항목이 다음 단계로 내려가므로 필요 없다.

## 맥락

ImageIO는 `META-INF/services`(모듈 경로에서는 `module-info`의 `provides`)로 SPI를 찾아 **인자 없는 생성자**로 만든다. 그래서 지금 플러그인 설정은 JVM 전역 값뿐이다: 시스템 프로퍼티 → 환경변수 → 기본값(엔드포인트, 데드라인, 클라이언트 픽셀 상한, grpc-api.md §5).

- 리더·writer가 처음 서버를 부를 때마다 다시 읽으므로 실행 중 `System.setProperty`로 바꿀 수는 있다. 하지만 문자열 기반이고, JVM 하나에 값이 하나뿐이다.
- 결정자가 "ImageIO에 등록할 때 설정을 함께 넘길 수 있는가"를 물었다(2026-10-29). 등록 단위로 넘길 방법이 없다.
- 호출 단위 옵션을 넘길 공개 타입도 없다. 예: 이 읽기만 데드라인 5초, 인코더 method 6. `WebPWriteParam`은 package-private이라 사용자는 ImageIO 표준 항목(손실/무손실, 품질)만 바꿀 수 있다.
- ADR-0017 대기 상한을 넘은 요청은 `UNAVAILABLE`("server busy")로 즉시 거부된다. 지금은 앱이 `IIOException` 메시지 문자열을 보고 혼잡을 구분해 다시 시도해야 한다. ADR-0017은 플러그인 자동 재시도 여부를 이 ADR로 넘겼다.

ImageIO가 허용하는 확장 지점은 세 가지다.

| 지점 | 범위 | 비고 |
|---|---|---|
| SPI 수동 등록: `IIORegistry.getDefaultInstance().registerServiceProvider(spi)` | 등록 단위(JVM 안 `ImageIO.read` 전체) | 같은 클래스의 SPI를 등록하면 기존 것을 내리고 대체한다. 레지스트리는 카테고리마다 클래스를 키로 하나만 둔다(JDK 25.0.5 `javax/imageio/spi/ServiceRegistry.java`의 `SubRegistry.registerServiceProvider`, 확인 2026-10-30). 그래서 등록 단위 설정은 JVM에 하나다 |
| `ImageReaderSpi.createReaderInstance(Object extension)` | 리더 하나 | `ImageIO.read`는 `null`로 부르므로 SPI를 직접 다루는 코드에서만 효과가 있다 |
| `ImageReadParam` / `ImageWriteParam` 하위 클래스 | 읽기·쓰기 한 번 | `reader.getDefaultReadParam()`으로 얻어 캐스팅하거나 `instanceof`로 확인한다 |

## 결정 동인

1. 기존 방식(시스템 프로퍼티·환경변수)은 그대로 동작해야 한다. 컨테이너와 Spring Boot에서 가장 쉬운 방법이다.
2. 타입이 있는 공개 API로, 등록 단위와 호출 단위를 모두 지원해야 한다.
3. 공개 API를 최소로 늘린다(ADR-0013: 공개 패키지는 `net.rivergod.webp.imageio` 하나, proto 코드는 공개하지 않는다).
4. 지금 proto(`webp.v1`)와 서버를 바꾸지 않는다(ADR-0019: proto 변경은 필드 추가만, 필요할 때).

## 검토한 선택지

1. **공개 설정 타입 + 세 확장 지점 모두**: 불변 설정 타입 하나를 SPI 생성자와 `createReaderInstance(extension)`이 받는다. 호출 단위 옵션은 공개 Param 하위 클래스로 넘긴다
2. 시스템 프로퍼티·환경변수만 유지하고, 프로그램에서 바꾸는 방법을 문서화한다
3. 정적 전역 설정 메서드(`WebPImageIO.configure(config)`) 하나만 추가한다
4. 호출 단위 Param만 추가한다

## 결정

**선택지 1을 추천한다.** ADR-0013의 "그 밖의 옵션은 필요해지면 새 ADR로 추가한다"에 따른 추가다. 공개 패키지 하나와 proto 비공개는 그대로다.

### 설정 타입

공개 불변 클래스 `WebPClientConfig`를 둔다. `WebPClientConfig.builder()`로 만든다.

| 항목 | 의미 | 값 범위 | 시스템 프로퍼티 / 환경변수 | 기본값 |
|---|---|---|---|---|
| `endpoint` | gRPC target | 빈 문자열 아님 | `webp.imageio.endpoint` / `WEBP_IMAGEIO_ENDPOINT` | `unix:///run/webp-imageio/webp.sock` |
| `deadlineMs` | 호출 하나의 데드라인(재시도 포함) | 1 이상 | `webp.imageio.deadline-ms` / `WEBP_IMAGEIO_DEADLINE_MS` | 30,000 |
| `maxPixels` | 클라이언트 픽셀 상한. 0이면 서버 상한만 | 0 이상 | `webp.imageio.max-pixels` / `WEBP_IMAGEIO_MAX_PIXELS` | 0 |
| `busyRetries` | "server busy" 재시도 횟수. 0이면 끔 | 0–10 | `webp.imageio.busy-retries` / `WEBP_IMAGEIO_BUSY_RETRIES` | 0 |

- 빌더에서 설정하지 않은 항목은 비어 있고, 다음 단계 값을 쓴다. 범위를 벗어난 값은 빌더가 `IllegalArgumentException`으로 거부한다.
- record로 두지 않는다. 비운 항목을 나타내려면 구성 요소가 null이나 `Optional`이어야 하는데, 둘 다 공개 record 구성 요소로 쓰기에 좋지 않다.

### 적용 순서

1. 리더·writer 단위 설정(`createReaderInstance(config)`)
2. 등록 단위 설정(SPI 생성자)
3. 시스템 프로퍼티
4. 환경변수
5. 기본값

- 항목마다 위에서부터 처음 정해진 값을 쓴다.
- 1과 2는 리더·writer를 만들 때 정해진다. 3–5는 지금처럼 리더·writer가 처음 서버를 부를 때 읽는다.
- 호출 단위 Param(아래)은 그 호출의 데드라인과 픽셀 상한만 바꾼다.

### 등록 단위

- `WebPImageReaderSpi(WebPClientConfig)`와 `WebPImageWriterSpi(WebPClientConfig)` 공개 생성자를 추가한다.
- 인자 없는 생성자(ServiceLoader가 쓰는)는 빈 설정과 같다. 지금 동작이다.
- 수동 등록하면 자동 등록된 같은 클래스의 SPI를 대체한다. JVM에 등록 단위 설정은 하나뿐이다.
- 여러 서버를 쓰는 앱(예: 테넌트별 서버)은 아래 리더 단위 설정을 쓴다.

### 리더·writer 단위

- `createReaderInstance(extension)`과 `createWriterInstance(extension)`이 `WebPClientConfig`를 받으면 그 설정을 1단계로 쓴다.
- 다른 타입이거나 `null`이면 SPI의 설정만 쓴다. `ImageIO.read`는 `null`로 부른다.

### 호출 단위

- `WebPReadParam`(공개, `ImageReadParam` 하위 클래스)을 추가한다.
  - 항목: 데드라인(ms), 클라이언트 픽셀 상한. 0이면 리더 설정을 쓴다.
  - `getDefaultReadParam()`이 이 타입을 돌려준다.
  - 표준 항목(영역, 서브샘플링 등)은 지금과 같다.
  - 애니메이션 순차 읽기(ADR-0014)는 스트림을 연 첫 읽기의 데드라인을 쓴다.
- `WebPWriteParam`을 공개한다.
  - 표준 항목(손실/무손실, 품질)에 더해 `method` 0–6을 받는다. 설정하지 않으면(-1) 서버 기본값을 쓴다.
  - `method`는 압축 모드와 상관없이 적용한다.
  - `getDefaultWriteParam()`이 이 타입을 돌려준다.
- lossless `exact`(투명 픽셀의 RGB 보존)는 넣지 않는다.
  - proto `EncodeParams`에 없는 항목이라 proto 필드 추가, 서버 구현, 이 ADR을 함께 바꿔야 한다.
  - 요구가 생기면 그때 다룬다.

### 혼잡 재시도(ADR-0017 "server busy")

| 항목 | 내용 |
|---|---|
| 대상 오류 | `UNAVAILABLE`이고 메시지가 "server busy"로 시작하는 것만. 서버가 일을 시작하기 전에 거부한 것이라 같은 요청을 다시 보내도 안전하다 |
| 대상 RPC | GetFeatures, Decode, DecodeAnimation 시작, Encode. 플러그인이 입력 전체를 쥐고 있어 다시 보낼 수 있다 |
| 제외 | EncodeAnimation. 프레임을 앱에서 하나씩 받아 바로 보내므로, 거부를 알았을 때 이미 넘긴 프레임을 다시 보낼 수 없다. 앱이 시퀀스 전체를 다시 쓴다 |
| 제외 | 서버 접속 불가(`UNAVAILABLE`의 다른 경우), 메모리 예산 "memory budget exhausted"(ADR-0016, 일하던 중의 실패), 데드라인 초과 |
| 물러섬 | 100 ms에서 시작해 두 배씩, 최대 1,600 ms |
| 시간 | 시도와 물러섬을 합쳐 호출 하나의 데드라인 안이다. 남은 시간이 다음 물러섬보다 짧으면 멈추고 마지막 오류를 낸다 |
| 기본값 | 0(끔). 지금 동작이 바뀌지 않는다 |

### 채널 공유

같은 엔드포인트의 gRPC 채널은 지금처럼 공유한다. 설정 객체마다 채널을 만들지 않는다.

### 결과

- 긍정: 기존 사용법은 그대로이고, 프로그램에서 타입 있는 설정을 등록·리더·호출 단위로 줄 수 있다. 서버를 여러 개 쓰는 앱은 리더 단위 설정으로 지원한다.
- 긍정: 혼잡한 서버에서 앱이 재시도 코드를 쓰지 않아도 된다(켰을 때). 꺼 두면 지금과 같다.
- 부정: 공개 타입이 셋 늘어난다: `WebPClientConfig`(와 빌더), `WebPReadParam`, 공개로 바뀌는 `WebPWriteParam`. 공개 API 문서와 호환 약속이 늘어난다.
- 부정: 수동 등록과 자동 등록이 섞이면 어떤 설정이 쓰이는지 헷갈릴 수 있다. README에 순서를 적고, 등록을 확인하는 테스트를 둔다.
- 부정: 재시도를 켜면 혼잡할 때 지연이 데드라인까지 늘 수 있다. 처리량은 서버 슬롯이 묶으므로 늘지 않는다.

### 확인 방법

- 단위 테스트
  - 적용 순서: 리더 → SPI → 프로퍼티 → 환경변수 → 기본값.
  - 빌더 범위 검사.
  - `createReaderInstance(config)`.
  - Param 값이 Decode/Encode 옵션과 호출 데드라인으로 가는지.
  - 재시도: 가짜 서버가 "server busy"를 N번 낸 뒤 성공하면 읽기·쓰기가 성공한다. 횟수를 넘으면 실패한다. 데드라인 안에서 멈춘다. 다른 오류는 다시 시도하지 않는다.
- 등록 테스트
  - 클래스패스: 설정을 준 SPI를 수동 등록하면 자동 등록된 것을 대체하고, `ImageIO.read`가 그 엔드포인트를 쓴다.
  - 모듈 경로(ModuleLayer): 공개 생성자로 만든 SPI의 리더가 그 엔드포인트를 쓴다.
- 통합 테스트: 서버 두 개를 띄우고(상한을 다르게) 리더 단위 설정으로 각각 읽는다.

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
- 같은 클래스 등록 시 대체: JDK 25.0.5 소스(`lib/src.zip`의 `java.desktop/javax/imageio/spi/ServiceRegistry.java`, `SubRegistry.registerServiceProvider`). 기존 제공자를 `deregisterServiceProvider`로 내린 뒤 클래스 키로 넣는다. 공개 문서의 반환값 설명("같은 클래스의 제공자가 없었으면 true")과도 맞다
