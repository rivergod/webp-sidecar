# 테스트 작성 규칙

널리 쓰이는 두 규칙(FIRST, AAA)을 기본으로 하고, 이 프로젝트에 필요한 규칙을 더한다.

## 1. FIRST 원칙

출처: Robert C. Martin, *Clean Code* 9장.

| 원칙 | 이 프로젝트에서의 의미 |
|---|---|
| **Fast** | 단위 테스트는 빨라야 한다. 실제 gRPC 서버나 컨테이너가 필요한 테스트는 통합 테스트로 분리한다. |
| **Independent** | 테스트끼리 실행 순서나 공유 상태에 의존하지 않는다. |
| **Repeatable** | 어떤 환경(Linux/Windows/CI)에서든 같은 결과를 낸다. 시간, 난수, 네트워크, 임시 파일 경로는 주입하거나 고정한다. |
| **Self-validating** | 성공/실패를 assert로 판정한다. 사람이 로그를 읽고 판단하게 하지 않는다. |
| **Timely** | 테스트를 프로덕션 코드와 함께(가능하면 먼저) 쓴다. [작업 원칙](working-principles.md)의 "목표 기반으로 실행한다"와 같은 이야기다. |

## 2. AAA (Arrange-Act-Assert) 구조

- 본문을 준비(Arrange) / 실행(Act) / 검증(Assert) 세 블록으로 나누고, 블록 사이에 빈 줄이나 `// given` / `// when` / `// then` 주석을 둔다.
- Act는 한 줄(하나의 동작)이다. 동작이 여러 개 필요하면 테스트를 나눈다.
- 테스트 하나는 하나의 동작(behavior)을 검증한다.
- 이름은 `대상_상황_기대결과` 형식으로 쓴다.
  - Java/Kotlin: `canDecodeInput_riffWithoutWebpFourcc_returnsFalse`
  - Rust: `can_decode_input_riff_without_webp_fourcc_returns_false`
  - Go: `TestCanDecodeInput` + 서브테스트 이름 `"riff without WEBP fourcc returns false"`

```java
@Test
void canDecodeInput_riffWithoutWebpFourcc_returnsFalse() throws IOException {
    // given
    ImageInputStream input = streamOf("RIFF", 0x0000_0010, "AVI ");

    // when
    boolean result = spi.canDecodeInput(input);

    // then
    assertFalse(result);
}
```

## 3. 테스트 계층

| 계층 | 범위 | 실행 조건 |
|---|---|---|
| 단위 | 로컬 로직만 (매직 바이트 검사, 설정 해석, 오류 매핑, 청크 조립 등) | 항상. 네트워크·컨테이너 없음 |
| 통합 | 실제 서버와 통신 (Testcontainers 등) | Docker가 있을 때 |
| 적합성 | libwebp 공식 테스트 벡터의 디코딩 결과와 비교 | 테스트 벡터를 받았을 때 |
| 벤치 | JMH, 별도 모듈(`bench/`) | 수동 또는 별도 작업 |

빌드 도구에서 계층별로 따로 실행할 수 있게 구성한다(예: 단위만 기본 `test`에 포함).

## 4. 이미지 결과 비교

- **lossless**는 픽셀 정확 일치로 비교한다.
- **lossy**는 허용 오차(PSNR 또는 SSIM 임계값)로 비교한다.
- 임계값은 이름 붙은 상수로 두고 근거를 주석으로 남긴다.

```java
// 품질 90 lossy 왕복(인코딩 → 디코딩) 결과와 원본의 최소 PSNR.
// 근거: <샘플 세트, libwebp 버전, 측정 최솟값>을 적는다. 측정 없이 정한 값은 쓰지 않는다.
static final double MIN_PSNR_DB_LOSSY_Q90_ROUNDTRIP = ...;
```

## 5. 손상/악성 입력 테스트는 반드시 포함한다

다음 경우를 최소로 다룬다. 서버와 클라이언트 양쪽에서 각자의 방어(서버: 디코딩 전 크기 검사, 클라이언트: 힙 할당 전 크기 검증)를 검증한다.

- 잘린 파일 (헤더 중간, 청크 중간, 비트스트림 중간)
- 크기 필드 조작 (RIFF 크기, 청크 크기가 실제와 다름)
- 거대 크기 선언 (포맷상 유효하지만 서버 상한을 넘는 크기 — 예: 16383×16383, VP8X 캔버스 필드 최대값)
- 애니메이션: 프레임 수·프레임 위치가 캔버스를 벗어나는 경우

## 6. 버그 수정은 재현 테스트를 먼저 추가한다

실패하는 재현 테스트를 먼저 커밋 단위에 포함하고, 그 테스트가 통과하도록 고친다.
