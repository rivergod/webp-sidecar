# 0011. Phase 5-2 성능 보강과 ADR-0011 재판정

- 상태: 초안
- 작성일/측정일: 2026-10-29
- 관련: [research/0009](0009-benchmark-results.md)(Phase 4 판정), [ADR-0011](../adr/0011-hypothesis-criteria.md)(기준, 바꾸지 않음), [ADR-0015](../adr/0015-benchmark-method.md)(측정 방법), [ADR-0018](../adr/0018-decode-output-buffer-pool.md)(버퍼 풀, proposed), [docs/roadmap.md](../roadmap.md) 5-2
- 원자료: [bench/results/2026-10-29-phase5/](../../bench/results/2026-10-29-phase5/)(`summary.md`, `results.csv`, `environment.txt`, `decode-time-*.tsv`)

## 요약

- **재판정: 18개 조건 중 16개 충족**(Phase 4: 12개).
  - 대 TwelveMonkeys 3배는 모두 충족한다(3.7–6.4배).
  - 대 JNI 50%는 사진 lossy M/L/XL(0.55–0.63)과 사진 alpha XL(0.56)이 새로 충족했다.
  - **남은 미달은 사진 alpha M(0.45)과 L(0.47) 두 조건이다.**
- **바꾼 것 넷**(결정자 동의, 2026-10-29):
  1. 클라이언트 픽셀 변환을 복사 없는 일괄 변환으로 바꿨다.
  2. 정지 이미지 첫 읽기에서 GetFeatures 왕복을 생략했다.
  3. 서버 출력 버퍼의 0 채우기를 없앴다.
  4. 서버 출력 버퍼를 풀로 재사용했다(ADR-0018, proposed).

  1–3은 `main`에 있다. 4는 `feat/server-buffer-pool` 브랜치에 있고, ADR 승인 뒤 병합한다. **이 재판정은 4를 포함한 빌드로 쟀다.**
- **하지 않은 것**:
  - 픽셀 포맷 BGRA8: 일괄 변환 뒤 변환이 클라이언트 프로파일의 3.5%라 이득이 작다.
  - SIMD 런타임 분기 빌드: libwebp 디코더에 해당 코드가 거의 없다(research/0009).
- 남은 alpha 격차의 원인: 서버 안 libwebp의 alpha 디코딩 자체가 JNI의 전체 처리보다 길다. M 기준 5.61 ms 대 4.02 ms다.
  - AVX2 빌드로도 줄지 않았다.
  - libwebp 버전(1.6.0 대 1.3.2)이나 출력 경로의 차이일 수 있으나 확인하지 않았다(미확인).

## 측정 조건

Phase 4와 같다(ADR-0015, [research/0009](0009-benchmark-results.md) "측정 조건"). 서버 컨테이너에는 Phase 5-1의 seccomp 프로파일이 적용돼 있다. 측정 코드는 커밋 `041bc67`(`feat/server-buffer-pool`)이다. 2026-10-29 03:10–05:14 UTC에 측정했다. 같은 실행 안에서 네 구현을 모두 다시 재고, 비율은 그 실행 안에서 계산했다.

## 바꾼 것과 근거

| 변경 | 근거(측정) | 위치 |
|---|---|---|
| 클라이언트 일괄 변환 | JFR 프로파일에서 클라이언트 시간의 53%가 바이트 4개씩 픽셀을 조립하는 변환이었다. 조각을 복사하지 않고 빅엔디언 `IntBuffer`로 한 번에 읽은 뒤 회전 한 번으로 RGBA → ARGB를 만든다. 변환 비중은 3.5%로 줄었다 | `ArgbFrameSink` |
| GetFeatures 왕복 생략 | 정지 이미지 첫 `read(0)`(기본 파라미터)는 Decode 하나만 부른다. 입력도 한 번만 보낸다. 애니메이션 여부는 파일 머리를 로컬에서 판단한다(RFC 9649 §2.7 VP8X 플래그). 크기와 alpha는 Decode 응답 헤더로 알고, 헤더 검증 뒤에 대상 이미지를 만든다 | `WebPImageReader` |
| 출력 버퍼 0 채우기 제거 | libwebp가 출력 영역을 모두 쓴다. 서버 안 디코딩 XL lossy 52.9 → 46.1 ms | `webp::decode_still_rgba_into` |
| 출력 버퍼 풀 | 같은 버퍼를 재사용하면 musl에서 페이지 폴트와 커널의 0 채우기가 없어진다. 서버 안 디코딩 XL lossy 46.1 → 36.4 ms. 총량 상한 128 MiB, 유휴 만료 30초. 부하 뒤 45초가 지나면 상주 메모리가 50 MB → 3.6 MB로 돌아왔다 | `server/src/pool.rs`, ADR-0018 |

정확성 확인:

- client 단위·통합 테스트와 서버 테스트를 통과했다. 서버 테스트에는 샘플 RGBA 해시 비교, 그리고 재사용 버퍼에 이전 픽셀이 남지 않는지 보는 테스트가 들어 있다.
- 벤치 검증에서 네 구현의 픽셀 일치 100건이 그대로다.
- seccomp를 적용한 상태의 격리 확인을 통과했다.

## 재판정 (사이드카 `sse2-uds`, ops/s)

| 내용 | 모드 | 크기 | 사이드카 | TwelveMonkeys | darkxanter (JNI) | 대 TM | 대 JNI | 판정 | Phase 4 대 JNI |
|---|---|---|---|---|---|---|---|---|---|
| photo | lossy | M | 195 | 38.2 | 354 | 5.11 | 0.55 | 충족 | 0.39 |
| photo | lossy | L | 87.2 | 15.7 | 151 | 5.55 | 0.58 | 충족 | 0.39 |
| photo | lossy | XL | 17.4 | 2.73 | 27.6 | 6.38 | 0.63 | 충족 | 0.43 |
| photo | lossless | M | 62.8 | 16.1 | 92.8 | 3.91 | 0.68 | 충족 | 0.55 |
| photo | lossless | L | 25.1 | 6.26 | 34.2 | 4.01 | 0.73 | 충족 | 0.62 |
| photo | lossless | XL | 4.60 | 1.14 | 5.53 | 4.04 | 0.83 | 충족 | 0.75 |
| photo | alpha | M | 113 | 24.2 | 249 | 4.66 | **0.45** | 미달 | 0.36 |
| photo | alpha | L | 49.2 | 9.32 | 105 | 5.28 | **0.47** | 미달 | 0.38 |
| photo | alpha | XL | 10.2 | 1.66 | 18.0 | 6.13 | 0.56 | 충족 | 0.45 |
| synthetic | lossy | M / L / XL | 79.0 / 30.9 / 5.72 | 21.3 / 8.08 / 1.39 | 105 / 39.4 / 6.86 | 3.71–4.11 | 0.75–0.83 | 충족 | 0.63–0.74 |
| synthetic | lossless | M / L / XL | 61.2 / 24.9 / 4.70 | 13.7 / 5.28 / 0.90 | 95.0 / 36.6 / 6.15 | 4.47–5.22 | 0.65–0.76 | 충족 | 0.52–0.65 |
| synthetic | alpha | M / L / XL | 67.3 / 26.6 / 4.83 | 16.8 / 6.42 / 1.09 | 101 / 38.0 / 6.04 | 4.01–4.43 | 0.67–0.80 | 충족 | 0.57–0.72 |

- 신뢰구간 반폭: 사이드카 ±2–7%, JNI ±0–5%(`summary.md`).
- 같은 조건의 JNI 값이 Phase 4보다 3–6% 높다(M lossy 337 → 354). 날짜가 달라서 생긴 차이로 보고, 비율은 같은 실행 안에서만 계산했다.
- **작은 크기**(판정 대상 아님): GetFeatures 생략으로 XS lossy가 6,399 → 10,033 ops/s, 대 TwelveMonkeys 1.11 → 1.68이다.

## 비용 분해 (photo, UDS, ms/op)

| 모드 | 크기 | 전체 | 서버 libwebp | 전송·기타 | 클라이언트 몫 | JNI 전체 | Phase 4 전체 |
|---|---|---|---|---|---|---|---|
| lossy | M | 5.12 | 2.73 | 2.01 | 0.38 | 2.83 | 7.69 |
| lossy | XL | 57.4 | 36.2 | 16.9 | 4.27 | 36.3 | 88.2 |
| lossless | M | 15.9 | 10.7 | 3.58 | 1.62 | 10.8 | 20.1 |
| lossless | XL | 217 | 173 | 27.9 | 16.7 | 181 | 256 |
| alpha | M | 8.87 | 5.61 | (미측정) | (미측정) | 4.02 | 11.6 |

- 서버 libwebp는 `decode_time` 버퍼 재사용 모드의 값이다. 전송·기타는 원시 Decode RPC에서 서버 libwebp를 뺀 값이고, 클라이언트 몫은 전체에서 원시 Decode RPC를 뺀 값이다.
- 원시 RPC 측정(`transport`)은 lossy와 lossless만 했다. 그래서 alpha의 전송·기타와 클라이언트 몫은 나누지 못했다.
- **클라이언트 몫은 lossy M에서 1.98 → 0.38 ms로 줄었다.** 남은 클라이언트 비용은 gRPC/protobuf가 받은 바이트를 `byte[]`로 복사하는 부분이다(JFR).
- **남은 큰 비용은 서버 libwebp(M lossy 53%)와 전송·gRPC(39%)다.**
- **alpha 격차**: 서버 libwebp(5.61 ms) 하나가 JNI 전체(4.02 ms)보다 길다.
  - AVX2 빌드로도 그대로다(AVX2/SSE2 시간 0.98–1.01, `decode-time-avx2.tsv`).
  - lossy만 쓴 경우(2.73 ms)와 비교하면 alpha 평면 디코딩과 합성에 약 2.9 ms가 더 든다.
  - 같은 일을 JNI(libwebp 1.3.2)는 훨씬 짧게 하는 것으로 보인다. 원인(버전 차이, 출력 경로)은 확인하지 않았다(미확인).

## Phase 5-3(proto 회고)에 넘길 것

| 항목 | 내용 |
|---|---|
| 판정 | 16/18 충족. 미달은 사진 alpha M(0.45)·L(0.47)뿐이고, 원인은 서버 안 libwebp alpha 디코딩 시간이다(IPC 구조가 아니다) |
| alpha 추가 조사 후보 | libwebp 1.3.2와 1.6.0의 alpha 디코딩 시간 비교(같은 빌드 조건), 출력 모드(RGBA/BGRA/ARGB)별 비교. 결과에 따라 libwebp 쪽 문제 보고나 설정 변경 |
| 전송·gRPC(M lossy 39%) | protobuf 수신 시 바이트 복사를 없애는 zero-copy 수신(grpc-java의 `Detachable`/`HasByteBuffer` + aliasing), 또는 공유 메모리 전송. 둘 다 구조 변경이 커서 이번 범위 밖이다 |
| ADR-0018 | 버퍼 풀 승인 여부. 이 재판정은 풀을 포함한 빌드로 쟀다. 풀만 켬/끔 비교(fork 1 빠른 확인): XL lossy +15%, alpha XL +8%(신뢰구간이 넓다) |
| BGRA8 | 하지 않는다(근거: 위 "요약") |

## 한계

Phase 4와 같다([research/0009](0009-benchmark-results.md) "한계"). 더해서 다음이 있다.

- 풀 켬/끔 비교는 ADR-0015 조건(fork 3)이 아니라 fork 1 빠른 확인이다.
- 픽셀 변환 측정(`ConversionBenchmark`)은 이번에 다시 재지 않았다. 클라이언트 변환이 바뀌어 Phase 4 값은 더 이상 맞지 않는다.

## 재현

```sh
git checkout feat/server-buffer-pool      # ADR-0018 승인·병합 뒤에는 main
bench/run.sh images
BENCH_RESULTS=bench/results/<이름> bench/run.sh jmh decode transport features
BENCH_RESULTS=bench/results/<이름> DECODE_TIME_REUSE=1 bench/run.sh decode-time
python3 -I bench/scripts/summarize.py bench/results/<이름>
```
