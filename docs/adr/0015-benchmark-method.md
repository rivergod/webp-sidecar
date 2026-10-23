# 0015. 벤치마크 방법: 환경, 샘플, SIMD 빌드, Wasm 비교 구현

- 상태: accepted
- 날짜: 2026-10-23
- 결정자: Ilkang Na
- 관련 문서: [ADR-0011](0011-hypothesis-criteria.md), [ADR-0001](0001-isolate-libwebp-in-grpc-sidecar.md), [ADR-0006](0006-test-samples.md), [ADR-0012](0012-server-runtime-config.md), [docs/research/0003-server-language-rust-vs-go.md](../research/0003-server-language-rust-vs-go.md)

## 맥락

Phase 4 결과로 가설을 판정한다(ADR-0011). 판정이 흔들리지 않으려면 측정 방법을 미리 정해야 한다. 특히 다음이 결과에 영향을 준다.

- `libwebp-sys` 기본 빌드는 x86_64에서 SSE2만 켠다. libwebp 자체 빌드는 SSE4.1/AVX2 객체를 따로 만든다([research/0003](../research/0003-server-language-rust-vs-go.md)).
- 합성 이미지는 압축률이 비현실적이다([research/0005](../research/0005-test-samples.md)).

## 결정

| 항목 | 결정 |
|---|---|
| 측정 호스트 | 이 개발 호스트(x86_64, 32코어, 메모리 61 GB). CPU 모델, OS, 커널을 결과와 함께 기록한다 |
| JDK | 벤치 실행은 JDK 25. 플러그인은 `--release 17`로 컴파일한 그대로 쓴다 |
| 서버 컨테이너 | ADR-0012 기본 제한 그대로(메모리 1 GiB, CPU 2) |
| JMH 설정 | fork 3, warmup 5 × 1 s, 측정 10 × 1 s, 모드 throughput(ops/s) |
| 사진 샘플 | public domain 또는 CC0로 명시된 파일만 쓴다. 받아서 쓰고 커밋하지 않는다(URL과 sha256은 manifest에 고정 — ADR-0006) |
| libwebp SIMD | 기본(SSE2) 빌드와 SSE4.1/AVX2 빌드를 둘 다 측정해 보고한다. **ADR-0011 판정은 기본 빌드로 한다**(보수적 판정). AVX2 빌드는 참고값이다 |
| Wasm(Chicory) 비교 구현 | libwebp v1.6.0을 wasi-sdk로 Wasm 빌드하고, Chicory 빌드타임 컴파일러(Wasm → JVM 바이트코드)로 측정한다. 인터프리터 모드 결과는 참고값이다 |
| JDeli | 결정자가 트라이얼을 확보해 요청하면 포함한다. 평가 용도로만 쓰고, 결과를 저장소 밖에 공개하기 전에 트라이얼 약관을 확인한다 |

### 결과

- 긍정: 측정 조건이 미리 고정돼 재현과 판정이 쉽다.
- 긍정: 기본 빌드로 판정하므로 특정 CPU 기능에 기대지 않는다.
- 부정: 한 호스트에서만 잰 결과다. 다른 하드웨어에서의 일반화는 보고서에 한계로 적는다.
- 부정: wasi-sdk 빌드와 Chicory 연결은 벤치 전용 작업량이 꽤 된다(추정).

### 확인 방법

- Phase 4: 보고서에 위 조건이 모두 기록되고, 한 명령으로 재현된다.

## 검토한 선택지

- SIMD: 기본 빌드만 / AVX2 빌드만 / **둘 다 측정, 판정은 기본 빌드**(채택)
- Wasm 도구: **wasi-sdk + Chicory 빌드타임 컴파일러**(채택) / Emscripten / Chicory 인터프리터만
- 측정 호스트: **개발 호스트**(채택) / 별도 클라우드 인스턴스(비용·재현성 관리 필요)

## 참고

- 벤치 세트 크기 클래스와 콘텐츠: [docs/research/0005-test-samples.md](../research/0005-test-samples.md) "권장 구성"
