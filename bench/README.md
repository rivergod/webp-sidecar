# bench

Phase 4 JMH 벤치마크. 같은 입력으로 다음을 비교한다. 측정 조건은 [ADR-0015](../docs/adr/0015-benchmark-method.md), 판정 기준은 [ADR-0011](../docs/adr/0011-hypothesis-criteria.md)을 따른다. 결과 보고서: [docs/research/0009-benchmark-results.md](../docs/research/0009-benchmark-results.md).

| 이름(`impl`) | 구현 | 비고 |
|---|---|---|
| `SIDECAR` | 이 프로젝트의 플러그인 → gRPC 사이드카(libwebp 1.6.0) | 서버는 `server` 파라미터로 고른다(아래) |
| `TWELVEMONKEYS` | TwelveMonkeys imageio-webp 3.15.3 | 순수 Java. WebP writer 없음 |
| `DARKXANTER` | darkxanter webp-imageio 0.3.3 | JNI. 내장 libwebp 1.3.2(SSE4.1 런타임 분기 포함 — 확인 방법은 보고서) |
| `WASM` | libwebp 1.6.0 Wasm + Chicory 1.7.5 빌드타임 컴파일 | ADR-0001 선택지 5의 비교 구현([wasm/README.md](wasm/README.md)). 벤치 전용 |
| `WASM_INTERPRETER` | 같은 Wasm, Chicory 인터프리터 | 참고값 |
| JDeli | 상용 | 결정자가 트라이얼을 확보해 요청하면 넣는다(평가 용도 한정, 결과 공개 전에 트라이얼 약관 확인) |

프로덕션 모듈과 분리된 모듈이다. 배포하지 않는다.

## 실행

Linux, Docker, `taskset`, Rust 툴체인(+ musl 타깃, `musl-gcc`)이 필요하다. 저장소 루트에서:

```sh
bench/run.sh            # 전체(이미지 → 입력 → 검증 → JMH 전 묶음 → 서버 디코딩 시간 → 요약), 약 4시간
bench/run.sh smoke      # 모든 묶음을 아주 짧게 돌려 연결만 확인(수치는 쓰지 않는다)
bench/run.sh jmh decode # 묶음 하나만
```

결과는 `bench/results/<UTC 시각>/`에 쌓인다: `summary.md`(판정 표), `results.csv`, `environment.txt`, `verify/*.md`(검증 표). JMH 원시 JSON과 로그는 커밋하지 않는다(`bench/.gitignore`).

단계별 명령과 환경변수(CPU 고정, 포트)는 [run.sh](run.sh) 머리 주석에 있다.

## 구성

| 경로 | 내용 |
|---|---|
| `build.gradle.kts` | JMH(어노테이션 프로세서), 비교 대상 의존성, Wasm 빌드·Chicory 코드 생성, `installBench`(실행 묶음) |
| `run.sh` | 한 명령 재현: 서버 컨테이너, 입력 생성, 검증 테스트, JMH 묶음, 환경 기록 |
| `scripts/summarize.py` | JMH JSON → `summary.md`, `results.csv` |
| `src/main/java/.../bench/` | 벤치마크(`DecodeBenchmark`, `EncodeBenchmark`, `AnimationBenchmark`, `TransportBenchmark`, `ConversionBenchmark`), 비교 대상 어댑터(`Decoders`), 입력(`BenchInputs`, `PhotoSources`) |
| `src/test/java/.../bench/` | 서버가 필요한 검증: 비교 대상 간 픽셀 일치(`DecoderAgreementTest`), TwelveMonkeys와 표준 메타데이터 트리 비교(`StandardMetadataComparisonTest`) |
| `wasm/` | libwebp Wasm 빌드(Dockerfile, shim) |

## 입력

`samples/bench/`(커밋하지 않음, [ADR-0006](../docs/adr/0006-test-samples.md)). 목록과 출처는 [samples/SOURCES.md](../samples/SOURCES.md) "커밋하지 않는 세트".

- 크기 클래스: XS 64², S 256², M 1024×768, L 1920×1080, XL 4000×3000
- 모드: lossy(`cwebp -q 75`), lossless(`cwebp -lossless`), alpha(`cwebp -q 75`, RGBA 입력)
- 내용: `photo`(public domain NASA 사진, [samples/bench.lock.json](../samples/bench.lock.json)), `synthetic`(그래디언트 + 노이즈)
- 애니메이션: 사진 팬 1024×768 × 12프레임, lossy(`img2webp -lossy -q 75 -d 100`)

## 서버(`server` 파라미터)

`run.sh`가 컨테이너를 띄우고 이름 → 엔드포인트를 `-Dwebp.bench.server.<이름>`으로 넘긴다. 모든 컨테이너는 deploy/README.md의 격리 옵션과 ADR-0012 기본 제한(메모리 1 GiB, `--cpus 2`)으로 뜨고, 같은 P코어 두 개(`--cpuset-cpus`)에 고정된다.

| 이름 | 이미지 | 전송 | 응답 조각 |
|---|---|---|---|
| `sse2-uds` | 기본 빌드(판정용) | UDS | 1 MiB |
| `sse2-tcp` | 기본 빌드 | TCP 127.0.0.1(`--network host`) | 1 MiB |
| `avx2-uds` | AVX2 빌드(참고) | UDS | 1 MiB |
| `sse2-uds-256k`, `-512k`, `-2m` | 기본 빌드 | UDS | 256 KiB, 512 KiB, 2 MiB |

TCP 서버는 파드 안 사이드카처럼 클라이언트와 같은 네트워크 네임스페이스의 루프백으로 잰다. Docker 포트 공개(docker-proxy)를 거치면 실제 배치에 없는 홉이 생긴다.
