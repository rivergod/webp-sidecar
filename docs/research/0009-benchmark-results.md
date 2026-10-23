# 0009. Phase 4 벤치마크 결과와 가설 판정

- 상태: 초안
- 작성일/측정일: 2026-10-23
- 관련 ADR: [ADR-0011](../adr/0011-hypothesis-criteria.md)(판정 기준), [ADR-0015](../adr/0015-benchmark-method.md)(측정 방법), [ADR-0001](../adr/0001-isolate-libwebp-in-grpc-sidecar.md), [ADR-0003](../adr/0003-transport.md), [ADR-0004](../adr/0004-pixel-transfer-format.md)
- 원자료: [bench/results/2026-10-23-phase4/](../../bench/results/2026-10-23-phase4/)(`summary.md` 판정 표 전체, `results.csv`, `environment*.txt`, `decode-time-*.tsv`, `verify/`)

## 요약

- **ADR-0011 판정: 18개 조건 중 12개 충족(부분 충족).**
  - 대 TwelveMonkeys 3배 기준은 M/L/XL 18개 조건 모두 충족했다(3.2–4.9배).
  - 대 JNI(darkxanter) 50% 기준은 lossless 전부와 합성 이미지 전부에서 충족했다(0.52–0.75).
  - **사진 lossy와 사진 lossy+alpha의 M/L/XL 6개 조건은 미달이다(0.36–0.45).**
  - ADR-0011 규칙에 따라 미달 조건을 Phase 5 회고 ADR에 적는다. 계속/중단 판단은 결정자가 한다.
- **미달 원인은 고정 비용이다.** 사진 lossy는 압축 파일이 작아(M 35 KB) libwebp 디코딩 자체가 가볍다. 그만큼 픽셀 전송과 클라이언트 처리의 비중이 커진다. M lossy 7.69 ms의 구성:

  | 항목 | 시간 | 비율 |
  |---|---|---|
  | 서버 안 libwebp | 3.34 ms | 43% |
  | 전송·gRPC | 2.29 ms | 30% |
  | 클라이언트(변환 0.51 ms 포함) | 1.98 ms | 26% |
  | GetFeatures | 0.08 ms | 1% |

  JNI는 같은 일을 2.97 ms에 한다. 서버 안 libwebp(musl, SSE2) 시간만으로도 JNI 전체보다 길다.
- **원인 중 하나는 서버의 할당자다.** 같은 디코딩을 glibc로 빌드하면 M lossy 3.34 → 2.82 ms, L 8.69 → 6.46 ms다. 컨테이너 이미지는 musl 정적 바이너리다.
- **전송 방식은 차이를 만들지 않는다.** UDS와 TCP가 원시 gRPC Decode에서 0.97–1.07배, 플러그인 전체에서 0.95–1.02배다. 단, smoke 측정에서 찾아 고친 TCP_NODELAY 버그를 반영한 뒤의 값이다.
- **SIMD**: AVX2 빌드는 서버 안 libwebp 디코딩을 M 이상에서 1–10% 줄이지만, 플러그인 전체 처리량에서는 차이가 없다(AVX2/SSE2 0.99–1.07, 같은 묶음 비교). 기본(SSE2) 빌드로 한 판정은 바뀌지 않는다.
- **Wasm(Chicory)은 ADR-0001 재검토 조건에 해당하지 않는다.** lossy와 alpha는 TwelveMonkeys와 비슷하고(0.85–1.26배), lossless는 TwelveMonkeys의 1.6–1.7배다. M 이상에서 사이드카가 Wasm보다 1.9–5.1배 빠르다.
- **인코딩(판정 대상 아님)**: 사이드카/JNI가 0.47–1.02배다. 큰 lossless에서는 같다(XL 3.86 s 대 3.94 s).
- **애니메이션(참고값)**: 1024×768 × 12프레임 전체 읽기가 사이드카 73 ms(캔버스 합성), TwelveMonkeys 290 ms(프레임 자체)다.

## 측정 조건

ADR-0015를 따른다. 값은 `environment.txt`에 기록된 것이다.

| 항목 | 값 |
|---|---|
| 호스트 | Intel Core i9-14900(P코어 8개 × 2스레드 = CPU 0–15, E코어 16개 = CPU 16–31), 메모리 61 GB, Ubuntu 26.04.1, Linux 7.0.0-38-generic, Docker 29.1.3 |
| CPU 주파수 | governor `powersave`(intel_pstate HWP), 터보 켜짐. 고정하지 않았다 |
| JDK | Temurin 25.0.4.1(벤치 실행). 플러그인은 `--release 17`로 컴파일한 그대로 |
| JMH | 1.37, fork 3, warmup 5 × 1 s, 측정 10 × 1 s, throughput(ops/s), 단일 스레드. fork JVM `-Xms8g -Xmx8g`, GC 기본(G1). 표의 ±는 99.9% 신뢰구간 반폭 |
| CPU 고정 | JMH JVM: CPU 0,2,4,6(P코어 4개, HT 형제 비움). 서버 컨테이너: CPU 12,14(P코어 2개) + `--cpus 2`. 모두 최대 5.5 GHz 코어다(5.8 GHz 코어 4·5번은 쓰지 않았다) |
| 서버 컨테이너 | deploy/README.md 격리 옵션(read-only, cap-drop ALL, no-new-privileges, 비루트), ADR-0012 기본 제한(메모리 1 GiB, CPU 2, 동시 처리 2) |
| 서버 빌드 | 기본: libwebp-sys 기본 빌드(x86-64 기준선 = SSE2), musl 정적. 참고: `simd-avx2`(libwebp 전체를 `-msse4.1 -mavx2`로 컴파일) |
| 전송 | UDS(바인드 마운트한 소켓, JNI 없는 NIO 경로). TCP는 `--network host`의 127.0.0.1(파드 안 사이드카와 같은 루프백 경로, docker-proxy 없음) |
| 응답 조각 | 기본 1 MiB. 비교: 256 KiB, 512 KiB, 2 MiB(`WEBP_SERVER_MAX_CHUNK_BYTES`) |

- 측정 코드는 커밋 `5d1817f`다.
- `environment.txt`의 `(dirty)`는 측정 시작 뒤 문서 인덱스(`docs/README.md`)를 고친 것 때문이다. 측정 코드와는 관계없다.
- `decode-servers`와 `decode-chunk` 묶음은 대조 재측정 값이다(아래 "전송 방식, SIMD 빌드, 응답 조각 크기"). 재측정 환경은 `environment-control.txt`에 있다.

## 비교 대상

| 이름 | 버전 | 구현 | libwebp / SIMD |
|---|---|---|---|
| 사이드카(이 프로젝트) | 0.1.0-SNAPSHOT | ImageIO 플러그인 → gRPC(UDS) → Rust 서버 | 1.6.0, 기본 빌드는 SSE2만 |
| TwelveMonkeys imageio-webp | 3.15.3 | 순수 Java | 해당 없음 |
| darkxanter webp-imageio | 0.3.3 | JNI, 내장 `libwebp-imageio.so` | 1.3.2, SSE4.1 경로 포함(아래) |
| Wasm(Chicory) | Chicory 1.7.5 | libwebp를 Wasm으로 빌드해 JVM 안에서 실행. 빌드타임 컴파일(Wasm → JVM 바이트코드) | 1.6.0, Wasm SIMD 없음, 스레드 없음 |

darkxanter 내장 라이브러리 확인(2026-10-23):

- jar 안 `native/Linux/x86_64/libwebp-imageio.so`의 `WebPGetDecoderVersion()`이 `0x010302`(1.3.2)를 돌려준다.
- 디스어셈블하면 SSE4.1 명령(`pblendvb` 5, `packusdw` 4, `pmovzxbw` 34)이 있고 AVX2(`ymm`)는 없다.
- libwebp는 실행 시 CPU를 검사해(`VP8GetCPUInfo`) SSE4.1 경로를 고른다. 그래서 이 호스트에서 JNI 쪽은 SSE4.1을 쓴다(추정 — JNI 쪽 실행 경로는 계측하지 않았다).
- 사이드카 기본 빌드에는 `pblendvb`가 없다(SSE4.1 미사용).
- 다만 libwebp 1.6.0 디코더의 SSE4.1·AVX2 코드는 적다(아래 "SIMD 경로 실행 확인"). 그래서 SSE4.1 유무는 디코딩 비교에 작은 영향만 준다.
- 기본 디코더 옵션: `useThreads=false`, `fancyUpsampling=true`. 사이드카 서버도 libwebp 기본값(스레드 없음, fancy upsampling)이다.

## 입력

`samples/bench/`(커밋하지 않음). 목록과 해시는 각 `index.json`, 출처는 [samples/SOURCES.md](../../samples/SOURCES.md).

- 크기: XS 64×64, S 256×256, M 1024×768, L 1920×1080, XL 4000×3000
- 모드:
  - lossy: `cwebp -q 75`
  - lossless: `cwebp -lossless`, q75 m4
  - alpha: `cwebp -q 75`, RGBA 입력 → VP8X + ALPH + VP8
- 내용:
  - `photo`: NASA 사진 1장(public domain, 6084×6084 JPEG)을 가운데 잘라 면적 평균으로 줄인 것. alpha는 원형 알파다. 압축 크기: lossy M 35 KB / XL 390 KB, lossless M 1.06 MB / XL 14.3 MB
  - `synthetic`: 그래디언트 + 결정적 노이즈. 압축이 어려운 쪽이다
- 애니메이션: 사진 팬 1024×768 × 12프레임, `img2webp -lossy -q 75 -d 100`

## 측정 대상의 정의

- **디코딩**: 메모리의 WebP 바이트 → `BufferedImage`.
  - ImageIO 플러그인은 `ImageIO.read`와 같은 순서로 매번 새 리더를 쓴다: 리더 생성 → `setInput(stream, true, true)` → `read(0)` → `dispose`.
  - Wasm은 인스턴스(선형 메모리) 하나를 스레드마다 재사용하고, 결과를 `TYPE_INT_ARGB`로 변환한다.
  - 사이드카의 gRPC 채널은 플러그인 안에서 재사용된다.
  - 이 플러그인은 읽기 한 번에 GetFeatures와 Decode 두 RPC를 부르고, 입력을 두 번 보낸다.
- **인코딩**: `BufferedImage`(불투명 `INT_RGB`, 알파 `INT_ARGB`) → WebP 바이트. 두 플러그인 모두 ImageIO 표준 파라미터(Lossy/Lossless, 품질 0.75, method 기본 4)를 쓴다.
- **비용 분해**:
  - 원시 gRPC Decode: 응답을 받기만 하고 변환하지 않는다(`TransportBenchmark`).
  - 원시 GetFeatures.
  - 서버 안 libwebp 디코딩: `server/examples/decode_time.rs`. 서버와 같은 함수, musl, 서버 CPU 하나에 고정, 50회 중앙값.
  - RGBA8 → INT_ARGB 변환: 이미지 할당 포함(`ConversionBenchmark`).

## 검증 — 같은 일을 재는가

`bench/src/test`(결과: `verify/`).

- **픽셀**: 벤치 세트 30개 전부에서 Wasm과 darkxanter의 결과가 사이드카와 **픽셀 단위로 같다**. lossy도 같다. libwebp 1.3.2/1.6.0, SSE2/SSE4.1/일반 C 경로 사이에 차이가 없었다. TwelveMonkeys는 lossless는 같고, lossy는 PSNR 36.3–46.3 dB다(YUV → RGB 변환이 다르다).
- **결과 이미지 타입**:

  | 구현 | 불투명 | 알파 |
  |---|---|---|
  | 사이드카 | `INT_RGB` | `INT_ARGB` |
  | darkxanter | `INT_RGB` | `INT_ARGB` |
  | TwelveMonkeys | `3BYTE_BGR` | `4BYTE_ABGR` |
  | Wasm 어댑터 | `INT_ARGB` | `INT_ARGB` |
- **애니메이션**: darkxanter는 애니메이션을 읽지 못한다(`VP8_STATUS_UNSUPPORTED_FEATURE`). 애니메이션 비교에서 뺐다.
- **표준 메타데이터 트리**(`javax_imageio_1.0`): 57개 파일에서 TwelveMonkeys와 노드 구성은 모두 같다. 값은 문서화된 두 경우만 다르다([design/imageio-metadata.md](../design/imageio-metadata.md) §3).

## 결과

### ADR-0011 판정 — 정지 이미지 디코딩, M/L/XL

사이드카는 `sse2-uds`(기본 빌드, UDS)다. 비율은 처리량(ops/s) 비율이고, 기준은 대 TM ≥ 3.0 그리고 대 JNI ≥ 0.50이다. 값 옆 괄호는 ms/op다.

| 내용 | 모드 | 크기 | 사이드카 | TwelveMonkeys | darkxanter (JNI) | Wasm | 대 TM | 대 JNI | 판정 |
|---|---|---|---|---|---|---|---|---|---|
| photo | lossy | M | 130 (7.69) | 37.0 | 337 (2.97) | 36.1 | 3.52 | 0.39 | 미달 |
| photo | lossy | L | 57.6 | 14.9 | 146 | 13.0 | 3.86 | 0.39 | 미달 |
| photo | lossy | XL | 11.3 (88.2) | 2.59 (387) | 26.3 (38.0) | 2.21 | 4.38 | 0.43 | 미달 |
| photo | lossless | M | 49.7 | 15.5 | 90.5 | 26.8 | 3.22 | 0.55 | 충족 |
| photo | lossless | L | 20.3 | 6.02 | 32.9 | 10.4 | 3.38 | 0.62 | 충족 |
| photo | lossless | XL | 3.90 (256) | 1.07 (933) | 5.19 (193) | 1.73 | 3.64 | 0.75 | 충족 |
| photo | alpha | M | 86.6 | 23.1 | 239 | 26.0 | 3.74 | 0.36 | 미달 |
| photo | alpha | L | 37.3 | 8.90 | 99.5 | 10.7 | 4.20 | 0.38 | 미달 |
| photo | alpha | XL | 7.75 (129) | 1.59 (630) | 17.4 (57.4) | 1.96 | 4.89 | 0.45 | 미달 |
| synthetic | lossy | M | 63.9 | 20.2 | 102 | 24.8 | 3.16 | 0.63 | 충족 |
| synthetic | lossy | L | 25.8 | 7.54 | 38.1 | 8.65 | 3.42 | 0.68 | 충족 |
| synthetic | lossy | XL | 4.90 | 1.32 | 6.63 | 1.50 | 3.71 | 0.74 | 충족 |
| synthetic | lossless | M | 45.3 | 12.7 | 87.3 | 22.0 | 3.57 | 0.52 | 충족 |
| synthetic | lossless | L | 19.2 | 5.06 | 34.1 | 7.92 | 3.80 | 0.56 | 충족 |
| synthetic | lossless | XL | 3.79 | 0.89 | 5.86 | 1.40 | 4.25 | 0.65 | 충족 |
| synthetic | alpha | M | 54.8 | 16.1 | 96.6 | 20.3 | 3.41 | 0.57 | 충족 |
| synthetic | alpha | L | 22.5 | 6.16 | 36.9 | 6.77 | 3.66 | 0.61 | 충족 |
| synthetic | alpha | XL | 4.17 | 1.02 | 5.81 | 1.27 | 4.07 | 0.72 | 충족 |

- 신뢰구간 반폭은 사이드카 ±3–8%, 나머지 ±1–7%다(`summary.md`).
- 미달 6개 조건 모두 대 TM 기준은 충족한다. 미달은 대 JNI 기준에서만 나온다.
- **판정 대상이 아닌 작은 크기(XS, S)**:
  - 사이드카는 TwelveMonkeys의 1.1–3.5배다.
  - JNI는 사이드카보다 2–5배 빠르다.
  - XS lossy의 사이드카 전체 시간은 0.156 ms이고, 그중 서버 libwebp가 0.044 ms다. 요청당 고정 비용은 약 0.1 ms다.

### 사이드카 디코딩 시간 분해 (photo, UDS, ms/op)

| 모드 | 크기 | 전체 | GetFeatures | Decode RPC | ↳ 서버 libwebp | ↳ 전송·기타 | 클라이언트 몫 | ↳ 변환(측정) | JNI 전체 |
|---|---|---|---|---|---|---|---|---|---|
| lossy | M | 7.69 | 0.08 | 5.63 | 3.34 | 2.29 | 1.98 | 0.51 | 2.97 |
| lossy | L | 17.4 | 0.10 | 12.4 | 8.69 | 3.76 | 4.81 | 1.36 | 6.86 |
| lossy | XL | 88.2 | 0.55 | 67.6 | 52.9 | 14.7 | 20.1 | 6.67 | 38.0 |
| lossless | M | 20.1 | 1.34 | 15.7 | 11.4 | 4.23 | 3.11 | 0.51 | 11.1 |
| lossless | L | 49.2 | 2.64 | 40.1 | 30.8 | 9.36 | 6.39 | 1.36 | 30.4 |
| lossless | XL | 256 | 11.8 | 223 | 204 | 19.6 | 21.3 | 6.67 | 193 |

- 각 열의 정의:
  - 클라이언트 몫 = 전체 − GetFeatures − Decode RPC
  - 전송·기타 = Decode RPC − 서버 libwebp
- 값의 출처와 주의:
  - 각 열은 서로 다른 묶음에서 쟀다(`summary.md` 전체 표).
  - 서버 libwebp는 gRPC 밖에서 잰 값이라 서버 안 다른 처리(입력 수집, 조각 복사)는 "전송·기타"에 들어간다.
- 관찰:
  - **서버 libwebp 시간(musl, SSE2)이 JNI 전체 시간과 비슷하거나 길다**(lossy M 3.34 대 2.97, lossless XL 204 대 193).
  - lossy는 그 위에 전송·기타(M 30%)와 클라이언트 몫(M 26%)이 더해져 JNI 대비 0.39가 된다.
- **GetFeatures**: 입력 크기에 비례한다. lossless XL(입력 14.3 MB)에서 11.8 ms(전체의 4.6%)이고, lossy에서는 0.1–0.6 ms다. 플러그인이 입력을 두 번 보내는 비용이다.
- **클라이언트 몫 중 변환 외 부분**: M lossy 1.47 ms, XL lossy 13.4 ms다. 응답 조각 복사, 흐름 제어, ImageIO 처리로 추정한다. 프로파일링하지 않았다(미확인).

### 서버 안 libwebp 디코딩 (gRPC 없음, photo, 중앙값 ms)

`decode-time-sse2.tsv`(musl, 컨테이너와 같은 빌드), `decode-time-sse2-glibc.tsv`(같은 코드를 glibc로 빌드), `decode-time-avx2.tsv`(musl, AVX2 빌드). 모두 CPU 12에 고정했다.

| 모드 | 크기 | musl SSE2(기본) | glibc SSE2 | musl AVX2 |
|---|---|---|---|---|
| lossy | M | 3.34 | 2.82 | 3.30 |
| lossy | L | 8.69 | 6.46 | 7.89 |
| lossy | XL | 52.9 | 50.7 | 49.5 |
| lossless | M | 11.4 | 11.7 | 11.1 |
| lossless | L | 30.8 | 30.3 | 30.4 |
| lossless | XL | 204 | 194 | 190 |
| alpha | M | 7.07 | 6.32 | 6.39 |
| alpha | L | 16.9 | 15.3 | 15.5 |
| alpha | XL | 91.1 | 85.8 | 85.9 |

- **할당자**: glibc가 lossy·alpha의 M/L에서 9–26% 빠르다.
  - musl 할당자는 큰 블록을 매번 mmap/munmap하므로 출력 버퍼(M 3 MB) 페이지 폴트가 요청마다 생긴다고 추정한다.
  - glibc는 해제된 mmap 크기에 맞춰 임계값을 올려 재사용한다(최대 32 MiB).
  - XL(48 MB)은 둘 다 mmap이라 차이가 작다. 원인 분석은 추정이고 계측하지 않았다.
- **SIMD**: AVX2 빌드(SSE4.1 포함)는 M 이상에서 1–10% 빠르다(lossless 1–7%).
- glibc 값은 별도 실행에서 쟀다. 같은 세션에서 musl과 번갈아 잰 값도 같은 경향이었다(M lossy 2.90 대 3.51 ms).

### SIMD 경로 실행 확인

AVX2 빌드가 효과가 작은 이유가 CPU 미지원인지, 경로를 타지 않아서인지 확인했다(2026-10-23).

- **CPU 지원**:
  - 이 CPU는 `sse4_1`, `sse4_2`, `avx2`를 지원한다(AVX-512 없음).
  - 측정에 쓴 CPU 0·12·14의 `/proc/cpuinfo`와 컨테이너 안 `/proc/cpuinfo` 모두에 `avx2`가 있다.
- **빌드에 들어간 함수**: `decode_time` 예제의 심볼을 봤다.
  - AVX2 빌드에는 `_SSE41` 함수 18개와 `_AVX2` 함수 13개가 있다. 기본 빌드에는 둘 다 0개다(`_SSE2`는 둘 다 113개).
  - libwebp 1.6.0에서 디코딩과 관련된 것은 다음뿐이다:
    - AVX2: lossless 역변환(`PredictorAdd*`, `TransformColorInverse`, `AddGreenToBlueAndRed`)
    - SSE4.1: 인트라 예측 `HE16`과 3바이트 RGB/BGR 출력 변환
  - RGBA 출력 변환(`UpsampleRgbaLinePair`)과 VP8 역변환에는 SSE2 판만 있다.
- **실행 중 호출**: gdb 중단점 호출 횟수로 확인했다. M 크기 한 번 디코딩, E코어 CPU 20에서 실행해 진행 중인 측정과 겹치지 않게 했다.

| 빌드 | 입력 | 호출된 함수(횟수) |
|---|---|---|
| AVX2 | lossless | `TransformColorInverse_AVX2` 49,152, `PredictorAdd1_AVX2` 2,624, `AddGreenToBlueAndRed_AVX2` 96, `PredictorAdd1_SSE2` 384 |
| AVX2 | lossy | `HE16_SSE41` 868, `UpsampleRgbaLinePair_SSE2` 770 |
| SSE2 | lossless | 같은 함수의 `_SSE2` 판(같은 횟수) |
| SSE2 | lossy | `HE16_SSE2` 868, `UpsampleRgbaLinePair_SSE2` 770 |

**결론**: AVX2 빌드는 실제로 AVX2·SSE4.1 경로를 탄다. 효과가 작은 것은 libwebp 1.6.0 디코더에 그 경로가 적기 때문이다. 특히 이 서버의 출력 형식(RGBA)에서 lossy 디코딩은 거의 모두 SSE2 코드다.

### 전송 방식, SIMD 빌드, 응답 조각 크기 (ADR-0003, ADR-0015)

원시 gRPC Decode(`TransportBenchmark`, UDS와 TCP를 같은 묶음에서 측정, photo, ms/op):

| 모드 | 크기 | UDS | TCP | TCP/UDS 시간 |
|---|---|---|---|---|
| lossy | XS | 0.090 | 0.094 | 1.04 |
| lossy | M | 5.63 | 5.64 | 1.00 |
| lossy | XL | 67.6 | 68.0 | 1.01 |
| lossless | XS | 0.159 | 0.171 | 1.07 |
| lossless | M | 15.7 | 15.3 | 0.98 |
| lossless | XL | 223 | 223 | 1.00 |

플러그인 전체(`DecodeBenchmark` SIDECAR, 세 서버를 같은 묶음에서 측정, photo, ops/s):

| 모드 | 크기 | sse2-uds | sse2-tcp | avx2-uds | TCP/UDS | AVX2/SSE2 |
|---|---|---|---|---|---|---|
| lossy | XS | 6,043 | 5,724 | 6,244 | 0.95 | 1.03 |
| lossy | M | 131 | 133 | 133 | 1.02 | 1.02 |
| lossy | XL | 11.5 | 11.5 | 11.4 | 1.00 | 1.00 |
| lossless | M | 49.7 | 49.3 | 49.4 | 0.99 | 1.00 |
| lossless | XL | 3.87 | 3.85 | 3.89 | 0.99 | 1.00 |
| alpha | M | 87.5 | 88.7 | 86.5 | 1.01 | 0.99 |
| alpha | XL | 7.78 | 7.81 | 7.83 | 1.00 | 1.01 |

- 모든 크기·모드에서 TCP/UDS 0.95–1.02, AVX2/SSE2 0.99–1.07이다. 신뢰구간(±3–8%) 안이라 **차이가 없다**고 본다(전체 표는 `summary.md`).
- 이 묶음의 기준값(`sse2-uds`)은 2시간 앞서 잰 `decode` 묶음과 거의 같다(M lossy 131 대 130, XL 11.5 대 11.3). 판정에 쓴 측정이 재현된다.
- AVX2는 서버 안 libwebp 시간을 1–10% 줄이지만, 플러그인 전체에서는 그 차이가 측정 오차에 묻힌다.

응답 조각 크기(`WEBP_SERVER_MAX_CHUNK_BYTES`, 네 크기를 같은 묶음에서 측정, photo, ops/s):

| 모드 | 크기 | 256 KiB | 512 KiB | 1 MiB (기본) | 2 MiB |
|---|---|---|---|---|---|
| lossy | M | 152 ±6% | 145 ±7% | 135 ±7% | 139 ±1% |
| lossy | L | 62.8 ±7% | 61.7 ±7% | 61.2 ±7% | 62.2 ±2% |
| lossy | XL | 11.9 ±7% | 11.7 ±7% | 11.7 ±7% | 12.7 ±1% |
| lossless | M | 53.1 ±4% | 52.2 ±4% | 50.5 ±3% | 51.8 ±3% |
| lossless | L | 21.1 ±4% | 21.0 ±3% | 21.1 ±3% | 21.3 ±3% |
| lossless | XL | 3.90 ±3% | 3.91 ±3% | 3.90 ±3% | 4.05 ±2% |

- 차이는 대부분 신뢰구간 안이고 방향도 일정하지 않다. M에서는 작은 조각이 조금 낫고(lossy 256 KiB +13%), XL에서는 2 MiB가 조금 낫다(lossy +8%, lossless +4%).
- 일관되게 나은 크기가 없다. 2 MiB는 메시지 하나가 gRPC 기본 한도(4 MiB)의 절반을 차지한다.

### Wasm (Chicory)

- 빌드타임 컴파일 기준이다(ADR-0015 측정값):
  - lossy와 alpha는 TwelveMonkeys와 비슷하다(M 이상 0.85–1.26배).
  - lossless는 TwelveMonkeys의 1.6–1.7배다.
- M 이상에서 사이드카가 Wasm보다 빠르다: lossless 1.9–2.7배, lossy와 alpha 2.6–5.1배.
- ADR-0011의 재검토 조건("Wasm 대안이 사이드카와 비슷한 속도를 내면")에 해당하지 않는다.
- 인터프리터는 빌드타임 컴파일보다 54–74배 느리다(참고값, XS–M).
- 이 Wasm 빌드는 Wasm SIMD와 스레드를 끈 것이다(Chicory 1.7.5는 SIMD를 인터프리터에서만 지원한다 — [bench/wasm/README.md](../../bench/wasm/README.md)).

### 정지 이미지 인코딩 (판정 대상 아님, photo)

| 모드 | 크기 | 사이드카 ms/op | darkxanter ms/op | 사이드카/JNI 처리량 |
|---|---|---|---|---|
| lossy | M | 43.8 | 36.9 | 0.84 |
| lossy | XL | 618 | 552 | 0.89 |
| lossless | M | 170 | 167 | 0.98 |
| lossless | XL | 3,864 | 3,941 | 1.02 |
| alpha | M | 98.7 | 77.6 | 0.79 |
| alpha | XL | 1,301 | 1,104 | 0.85 |

- 작은 크기에서는 0.47–0.80이다(XS, S).
- 인코딩은 libwebp 작업이 무거워 고정 비용의 비중이 작다. 큰 lossless에서는 JNI와 같다.
- 출력 크기는 비교하지 않았다. 설정은 같지만 libwebp 버전(1.6.0 대 1.3.2)과 lossless `exact` 설정이 다르다(이 서버는 lossless에서 `exact=1`).

### 애니메이션 (참고값)

1024×768 × 12프레임 lossy 전체 읽기:

| 구현 | 처리량 | 시간 | 프레임당 |
|---|---|---|---|
| 사이드카 | 13.7 ops/s | 73 ms | 6.1 ms |
| TwelveMonkeys | 3.44 ops/s | 290 ms | — |

사이드카는 합성한 캔버스를, TwelveMonkeys는 ANMF 프레임 자체를 돌려주므로 하는 일이 다르다.

## 벤더 공개 수치와 비교

[research/0001](0001-java-webp-landscape.md)의 IDR Solutions 수치에서 darkxanter 읽기 처리량은 TwelveMonkeys의 19.3배다. 이 측정에서는 다음과 같다(같은 비율, M/L/XL).

| 내용 | 모드 | darkxanter/TwelveMonkeys |
|---|---|---|
| photo | lossy | 9.1–10.2배 |
| photo | lossless | 4.8–5.9배 |
| photo | alpha | 10.3–11.2배 |
| synthetic | 전체 | 5.0–6.9배 |

IDR 표는 코퍼스, 하드웨어, JDK, 라이브러리 버전을 밝히지 않았다. IDR 벤치 저장소에는 TwelveMonkeys 3.9.4와 darkxanter 0.3.1이 남아 있다. 그래서 차이의 원인은 가릴 수 없다. TwelveMonkeys가 3.9.4 이후 빨라졌을 가능성이 있다(추정). 인코딩 수치도 코퍼스를 몰라 직접 비교하지 않는다.

## ADR-0003 확인 방법에 대한 결론

- **UDS 대 TCP**:
  - 루프백에서 같다(원시 gRPC Decode 0.97–1.07배, 플러그인 전체 0.95–1.02배).
  - UDS 기본값을 유지하는 이유는 속도가 아니라 접근 제어다. 파일 권한으로 막을 수 있고 네트워크에 노출되지 않는다.
  - TCP 옵션도 성능상 불리하지 않다.
- **TCP_NODELAY**: 서버가 받은 TCP 연결에 Nagle을 끄지 않아 요청마다 약 40 ms가 늦던 버그를 이번 측정에서 찾아 고쳤다(아래).
- **응답 조각 크기**:
  - 256 KiB–2 MiB 사이에서 일관된 차이가 없다(대부분 신뢰구간 안, M과 XL에서 방향이 반대).
  - 초기값 1 MiB를 유지한다. 바꿀 근거가 없다.
- **JNI 없는 경로**: 모든 UDS 측정이 JNI 없는 NIO 경로다.

## ADR-0004 확인 방법에 대한 결론

- **변환(RGBA8 → INT_ARGB, 할당 포함)**: 플러그인 전체 시간의 6.6%(M lossy), 7.6%(XL lossy), 2.6%(XL lossless)다.
- **전송·기타(서버 libwebp를 뺀 Decode RPC)**: 30%(M lossy), 17%(XL lossy), 21%(M lossless), 7.6%(XL lossless)다.
- **판단**:
  - 변환과 전송을 합하면 lossy에서 의미 있는 비율(M 37%)이다.
  - 다만 RGB8로 줄일 수 있는 것은 그 일부다(불투명 이미지의 전송량 25%). 예상 절감은 많아야 약 9%로, 단독으로는 대 JNI 기준을 넘기지 못한다.
  - `TYPE_4BYTE_ABGR`과 같은 바이트 순서(ABGR8)로 받으면 클라이언트 변환(약 7%)을 없앨 수 있다.
  - 두 포맷은 proto 변경이므로 Phase 5 proto 회고에서 다른 개선 후보와 함께 측정해 새 ADR로 제안한다. 이번에는 ADR을 쓰지 않는다.

## Phase 5 회고에 넘길 것

대 JNI 50%가 되려면 사이드카가 M lossy에서 5.94 ms 이하(지금 7.69 ms), XL lossy에서 76 ms 이하(지금 88 ms)여야 한다. 측정으로 근거가 있는 개선 후보는 다음과 같다(효과는 각 항목을 따로 잰 값이고, 합산 효과는 미확인).

| 후보 | 근거 | M lossy 예상 효과 |
|---|---|---|
| 서버 할당자(musl → glibc 기반 이미지, 또는 musl + 다른 할당자) | 서버 libwebp 3.34 → 2.82 ms | −0.5 ms |
| GetFeatures 왕복 제거(Decode 응답 헤더로 크기 확인) | GetFeatures 0.08 ms(lossy), 11.8 ms(lossless XL) | −0.1 ms(lossless에서 더 큼) |
| 클라이언트 경로 프로파일링(변환 외 1.5 ms) | 클라이언트 몫 1.98 − 변환 0.51 | 미확인 |
| 픽셀 포맷 ABGR8 / RGB8 | 변환 0.51 ms, 전송량 −25% | −0.5 ~ −1 ms(추정) |
| libwebp SIMD(런타임 분기 빌드) | AVX2 빌드 1–10% | −0.0 ~ −0.3 ms |

미달 조건과 이 표는 ADR-0011 규칙에 따라 Phase 5 회고 ADR의 입력이다.

## 측정 중 발견해 고친 문제

1. **서버 TCP에 `TCP_NODELAY`가 꺼져 있었다.**
   - 증상: smoke 측정에서 64×64 Decode가 TCP 42 ms, UDS 0.6 ms였다.
   - 원인: tonic은 직접 넘긴 리스너 스트림에 소켓 옵션을 걸지 않아, Nagle 알고리즘과 클라이언트 지연 ACK가 겹쳤다.
   - 수정: `TcpIncoming::with_nodelay(true)`로 고치고 재현 테스트를 넣었다(브랜치 `fix/server-tcp-nodelay`). 수정 후 같은 조건에서 약 0.1 ms다.
   - 본 측정은 수정 후 이미지로 했다.
2. **애니메이션 프레임의 표준 메타데이터가 반환 이미지와 맞지 않았다.**
   - `read(i)`는 투명 캔버스에 합성한 ARGB를 돌려주는데, 표준 포맷은 프레임 비트스트림의 알파를 따랐다.
   - TwelveMonkeys와 트리를 비교하다 발견해 고쳤다(브랜치 `fix/anim-standard-metadata`).
3. **처음 측정한 서버 비교(`decode-servers`, `decode-chunk`)는 기준값을 다른 묶음에서 가져왔다.**
   - 1–2시간 차이로 생기는 흔들림과 구분되지 않았다.
   - 기준 서버를 같은 묶음에 넣고 다시 쟀다. 첫 측정은 `*-run1.json`으로 남겼다.

## 한계

- 한 호스트(하이브리드 CPU, P코어 고정)에서 단일 스레드로 쟀다. 다음은 재지 않았다:
  - 동시 요청에서의 처리량(서버 CPU 2, 동시 처리 2 제한의 영향)
  - 첫 호출(채널 연결, JIT 전) 지연
  - GC 부담
- 사진은 1장이다. 내용에 따라 lossy 디코딩 비용과 압축률이 달라진다.
- CPU 주파수를 고정하지 않았다(터보 켜짐). 같은 묶음 안 비교는 이 영향을 줄이지만 묶음 사이 비교(비용 분해 표)에는 남는다.
- darkxanter의 libwebp는 1.3.2다. 버전 차이에 따른 libwebp 자체 속도 차이는 분리하지 않았다.
- Wasm은 인스턴스 하나를 재사용한 값이다. 요청마다 인스턴스를 만들면 더 느리다.

## 재현

```sh
bench/run.sh            # 전체(약 4시간). 결과: bench/results/<UTC 시각>/
bench/run.sh jmh decode-servers decode-chunk   # 서버 비교 묶음만
```

필요한 것과 단계별 명령: [bench/README.md](../../bench/README.md). glibc 비교(`decode-time-sse2-glibc.tsv`)는 run.sh에 없는 수동 측정이다: `cd server && cargo build --release --example decode_time --target-dir target/glibc` 후 같은 파일 목록으로 `taskset -c 12`로 실행했다.
