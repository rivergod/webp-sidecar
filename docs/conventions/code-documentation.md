# 주석과 문서화 규칙

적용 범위: `proto/`, `server/`, `client-jvm/`, `bench/`의 코드. `spikes/`는 버릴 코드라 적용하지 않는다.

## 0. 언어

- 문서와 주석은 한국어로 쓴다.
- 식별자, 로그 메시지, 오류 메시지(예외 메시지, gRPC status message)는 영어로 쓴다. 로그 검색과 외부 도구 호환을 위해서다.

## 1. 공개 API에는 반드시 문서 주석을 단다

언어별 표준 형식을 따른다.

| 언어 | 형식 | 예 |
|---|---|---|
| Java | Javadoc (`/** ... */`, `@param` / `@return` / `@throws`) | `/** WebP 매직 바이트만 검사한다. 네트워크를 쓰지 않는다. */` |
| Kotlin | KDoc | |
| Rust | rustdoc — 항목은 `///`, 모듈은 `//!` | `/// 디코딩 전에 캔버스 크기를 검사한다.` |
| Go | godoc — 식별자 이름으로 시작하는 문장 | `// DecodeStill decodes ...` |
| Protobuf | 서비스, RPC, 메시지, 필드, oneof, enum, enum 값마다 선행 주석 | `// 디코딩 결과 픽셀의 너비. 단위: 픽셀.` |

문서 주석에는 해당하는 것을 담는다.

- 목적
- 파라미터와 반환값의 의미와 **단위**(바이트, 픽셀, ms)
- 오류/예외 조건 (어떤 입력에서 무엇을 던지거나 어떤 status를 돌려주는가)
- 스레드 안전성
- 소유권과 수명 (Rust)
- 정지 조건 (스트림: 몇 개의 메시지가 어떤 순서로 오고 언제 끝나는가)

## 2. 블록 단위 주석

함수 안에서 논리적으로 구분되는 블록(검증 → 변환 → 전송 등) 앞에 그 블록이 **무엇을 왜** 하는지 한두 줄로 적는다.

```java
// 헤더의 캔버스 크기로 상한을 먼저 확인한다. 픽셀 버퍼를 할당하기 전에 거부해야 bomb 방어가 된다.
if ((long) header.getWidth() * header.getHeight() > limits.maxPixels()) { ... }
```

코드를 그대로 읽어 주는 주석(`// i를 1 증가`)은 쓰지 않는다.

## 3. 비자명한 결정에는 근거를 남긴다

관련 ADR이 있으면 번호를 쓴다.

```rust
// ADR-0003: gRPC 기본 메시지 한도(4MB) 아래로 유지하려고 1MB 청크로 나눈다.
const CHUNK_SIZE: usize = 1 << 20;
```

ADR이 없는 결정이 반복해서 필요하면 ADR을 먼저 쓴다.

## 4. WebP 스펙 의존 코드는 RFC 9649 절을 참조한다

```go
// RFC 9649 §2.7: VP8X의 캔버스 너비/높이는 24비트 "값 - 1"로 저장된다.
```

절 번호 대응표는 [docs/research/0005-test-samples.md](../research/0005-test-samples.md)의 "RFC 9649 절 대응표"에 있다. RFC와 libwebp 동작이 다른 곳(예: ALPH 예약 비트, 애니메이션 배경색)에서는 어느 쪽을 따르는지 주석에 함께 적는다.

## 5. 린터로 강제한다

문서 주석 누락은 리뷰가 아니라 린터가 잡는다. 후보는 다음과 같고, 구체적인 도구 선택은 언어 ADR(ADR-0002, ADR-0005)이 확정된 뒤에 정한다.

| 대상 | 후보 |
|---|---|
| Protobuf | `buf lint`의 `COMMENTS` 규칙 묶음 |
| Rust | `#![warn(missing_docs)]` (CI에서는 `-D warnings`) |
| Go | `revive`의 `exported` 규칙 |
| Java | Checkstyle (`MissingJavadocType`, `MissingJavadocMethod` 등) |
| Kotlin | detekt (`UndocumentedPublicClass`, `UndocumentedPublicFunction` 등) |
