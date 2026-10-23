# 0011. 가설 판정 기준: 디코딩 처리량이 TwelveMonkeys의 3배 이상, JNI의 50% 이상

- 상태: accepted
- 날짜: 2026-10-23
- 결정자: Ilkang Na
- 관련 문서: [ADR-0001](0001-isolate-libwebp-in-grpc-sidecar.md), [docs/roadmap.md](../roadmap.md) Phase 4·5, [docs/research/0005-test-samples.md](../research/0005-test-samples.md) "권장 구성"(벤치 세트)

## 맥락

ADR-0001은 "사이드카가 순수 Java보다 의미 있게 빠르지 않으면 가설이 틀린 것"이라고만 적었다. 측정 결과를 본 뒤 기준을 정하면 해석이 결과에 끌려가므로, Phase 4 측정 전에 숫자로 정해 둔다.

## 결정 동인

1. 측정 전에 정해야 한다.
2. 이 프로젝트의 비교 대상(순수 Java, JNI)에 대해 각각 의미가 있어야 한다.
3. 큰 이미지에서 IPC 비용이 속도 이점을 얼마나 깎는지가 핵심 질문이다.

## 검토한 선택지

1. **두 기준을 모두 만족하면 가설 유지**: TwelveMonkeys 대비 3배 이상, darkxanter(JNI) 대비 50% 이상
2. TwelveMonkeys 대비 기준만 둔다
3. 기준을 정하지 않고 Phase 5 회고에서 판단한다

## 결정

**선택지 1을 택한다.**

- 대상: **디코딩** 처리량(JMH, ops/s). TwelveMonkeys에는 WebP writer가 없으므로 인코딩은 판정 기준에서 뺀다(인코딩 결과는 보고서에 기록만 한다).
- 범위: 1024×768 이상인 크기 클래스(벤치 세트의 M, L, XL).
- 이 프로젝트의 플러그인은 기본 전송(UDS)으로 잰다.
- **TwelveMonkeys imageio-webp의 3배 이상**이고 **darkxanter webp-imageio(JNI)의 50% 이상**이면 가설을 유지한다.
- 크기 클래스나 이미지 종류(lossy, lossless, lossy+alpha)별로 기준에 못 미치는 경우가 있으면, 그 조건을 Phase 5 회고 ADR에 적고 계속/중단 판단에 반영한다.
- libwebp Wasm + Chicory(ADR-0001)와 JDeli 결과는 판정 기준이 아니라 참고값이다. 다만 Wasm 대안이 사이드카와 비슷한 속도를 내면 ADR-0001의 선택을 다시 검토한다.

### 결과

- 긍정: Phase 4 결과를 미리 정한 기준으로 판정한다.
- 부정: 기준 숫자 자체(3배, 50%)는 측정 근거가 아니라 목표값이다. 벤치 환경(Phase 4 착수 시 결정)에 따라 결과가 달라질 수 있다.

### 확인 방법

- Phase 4 결과 보고서에 크기 클래스·이미지 종류별로 두 비율(대 TwelveMonkeys, 대 JNI)을 표로 적고 기준 충족 여부를 표시한다.

## 선택지별 장단점

### 1. 두 기준 (채택)

- 장점: 순수 Java보다 확실히 빠른지와 JNI에 비해 IPC 비용이 감당할 만한지를 함께 본다.
- 단점: 기준이 두 개라 일부만 충족하는 경우의 해석이 필요하다(위 규칙으로 처리).

### 2. TwelveMonkeys 기준만

- 장점: 단순하다.
- 단점: JNI 대비 비용을 판단할 근거가 없다.

### 3. 기준 없음

- 장점: 측정 뒤 유연하게 판단한다.
- 단점: 결과에 맞춰 기준을 정하게 될 위험이 있다.

## 참고

- 벤치 세트 크기 클래스: [docs/research/0005-test-samples.md](../research/0005-test-samples.md)
