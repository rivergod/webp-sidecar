# 0002. 서버 구현 언어: Rust (tonic + libwebp-sys)

- 상태: proposed
- 날짜: 2026-10-23
- 결정자: -
- 관련 문서: [docs/research/0003-server-language-rust-vs-go.md](../research/0003-server-language-rust-vs-go.md), [spikes/README.md](../../spikes/README.md), [ADR-0008](0008-libwebp-version-pinning.md)

## 맥락

서버는 libwebp를 직접 링크하고 상위 API(`WebPAnimDecoder`, `WebPAnimEncoder`, `WebPDemux`, `WebPMux`)까지 써야 한다. 큰 픽셀 버퍼(4K ≈ 33MB)를 청크로 스트리밍하고, 컨테이너 하드닝(read-only, 네트워크 없음, non-root, seccomp)을 적용한다. 후보는 Rust와 Go다.

두 언어 모두 Docker 스파이크로 같은 확인을 통과했다([research/0003 "스파이크 결과"](../research/0003-server-language-rust-vs-go.md)): `WebPGetFeatures`로 크기 선검사 후 거부(16384×16384 선언 파일), 정지 이미지 디코딩, Mux로 ICCP/EXIF/XMP 읽기, `WebPAnimDecoder`로 3프레임 합성. 두 쪽 모두 libwebp 1.6.0을 링크했다.

기준별 결과 요약(상세와 출처는 research/0003):

| 기준 | Rust | Go |
|---|---|---|
| libwebp 바인딩 | `libwebp-sys` 0.14.4가 1.6.0을 벤더링하고 cargo 안에서 C까지 빌드. 필요한 API 전부 원시 FFI | Anim과 Mux 청크를 함께 다루는 유지 바인딩 없음. 자체 cgo 선언 + libwebp 별도 빌드 |
| gRPC | tonic 0.14.6 (0.x) | grpc-go v1.84.0 (공식 1.x) |
| FFI | 일반 C 호출 비용 | 호출당 수십 ns, 호출 중 OS 스레드 점유, 포인터 규칙 런타임 검사 |
| 메모리 | GC 없음, Drop으로 C 버퍼 해제, prost `Bytes`로 수신 복사 감소 | C 메모리는 GC·GOMEMLIMIT 밖, protobuf-go가 `bytes` 복사 |
| 이미지 크기 (스파이크) | musl 정적 + scratch 0.42MB | glibc 정적 + distroless/static 2.29MB |
| 시스템 콜 (스파이크 CLI) | 18~25종 | 33~36종 |
| 퍼징 | cargo-fuzz, libwebp까지 계측 가능(추정) | `go test -fuzz`는 cgo의 C 코드를 계측하지 않음 |
| 팀 친숙도 | **미확인 — 사람에게 확인 필요** | **미확인** |

## 결정 동인

1. libwebp 상위 API(Anim, Mux, Demux)를 무리 없이 쓸 수 있어야 한다.
2. 큰 픽셀 버퍼의 메모리 사용을 예측하고 제한할 수 있어야 한다(컨테이너 메모리 제한).
3. 하드닝: 작은 이미지, 작은 시스템 콜 표면, 정적 링크.
4. gRPC 스택이 UDS, 스트리밍, 헬스체크를 지원하고 충분히 안정적이어야 한다.
5. 퍼징으로 입력 경로를 검증할 수 있어야 한다(Phase 5).
6. 팀이 유지보수할 수 있어야 한다(사람의 답 필요).

## 검토한 선택지

1. **Rust: tonic + prost + `libwebp-sys`(원시 FFI) 위에 자체 안전 래퍼**
2. Go: grpc-go + 자체 cgo 래퍼 + libwebp 소스 빌드
3. Go: `gen2brain/webp`(libwebp를 WASM → Go로 변환, cgo 없음)
4. Rust: 자체 `-sys` 크레이트(libwebp를 직접 빌드)

## 결정

**선택지 1(Rust)을 추천한다. 단, 팀 친숙도 확인 결과가 이를 뒤집지 않는다는 조건에서다.** 아래 질문의 답이 "Rust 유지보수 인력이 없다"이면 선택지 2(Go)를 추천한다. Go도 스파이크로 실현 가능성이 확인됐다.

사람에게 확인할 질문:

1. 서버를 유지보수할 사람의 Rust / Go 실무 경험은 어느 정도인가?
2. unsafe Rust 또는 cgo 래퍼(메모리·수명 규칙)를 작성·리뷰할 수 있는 사람이 있는가?
3. 이미 쓰는 CI, 레지스트리, SBOM/취약점 스캐너가 어느 생태계를 지원하는가?
4. 프로토타입 이후 서버를 누가 맡고, 그 팀의 주 언어는 무엇인가?
5. tonic 또는 grpc-go를 운영해 본 경험이 있는가?
6. 의존성 정책상 0.x 크레이트(tonic)나 소유자 1명인 크레이트(`libwebp-sys`)를 허용하는가? 필요하면 포크를 유지할 의향이 있는가?

Rust를 택할 때의 구현 조건:

- libwebp 호출은 서버 안의 작은 모듈 하나에 모으고, 그 모듈만 `unsafe`를 쓴다. C enum 반환값은 Rust enum이 아니라 정수로 받는다(`libwebp-sys`가 생성한 Rust enum에 예상 밖 값이 들어오면 UB).
- `libwebp-sys`가 감싸지 않은 inline 헬퍼(`WebPMuxCreate`, `WebPDemux`, `WebPAnimEncoderNew` 등)는 `*Internal` 함수 + ABI 상수로 호출하고, 그 이유를 주석으로 남긴다.
- `libwebp-sys` 기본 빌드는 x86_64에서 SSE2만 켠다. 벤치마크(Phase 4) 전에 SIMD 설정이 공정한지 확인한다(libwebp 자체 빌드는 SSE4.1/AVX2 객체를 따로 만든다).
- libwebp 버전 고정 방식은 [ADR-0008](0008-libwebp-version-pinning.md)을 따른다. 태그가 아닌 커밋을 고정하게 되면 선택지 4(자체 `-sys`)로 옮긴다.

### 결과

- 긍정: libwebp 빌드가 cargo 안에서 끝나고, 상위 API 전부를 원시 FFI로 바로 쓴다.
- 긍정: GC가 없어 큰 버퍼의 메모리 사용이 예측 가능하다. 정적 이미지와 시스템 콜 표면이 작아 seccomp 프로파일 작성이 쉽다.
- 부정: 안전 래퍼를 직접 써야 한다(어느 언어든 마찬가지지만 Rust는 `unsafe` 경계 설계가 필요하다).
- 부정: tonic은 0.x이고 저장소가 grpc/grpc-rust로 옮겨지며 breaking change가 예고돼 있다. 버전 고정과 업그레이드 비용을 감수한다.
- 부정: `libwebp-sys`는 소유자가 1명이다. 유지가 멈추면 포크하거나 자체 `-sys`로 옮긴다.
- 부정: 어느 언어든 libwebp 안의 segfault는 서버 프로세스 전체를 죽인다. 언어 선택으로 요청 단위 격리를 얻지 못한다(ADR-0001의 부정적 결과와 같다).

### 확인 방법

- 위 6개 질문에 대한 사람의 답을 [research/0003 8절](../research/0003-server-language-rust-vs-go.md)에 기록한다.
- Phase 1 완료 기준(서버 단위 테스트, 상한 검사, Docker 하드닝 실행)을 이 언어로 통과한다.
- Phase 5에서 퍼저가 libwebp 호출 경로까지 계측되는지 확인한다(현재는 추정).

## 선택지별 장단점

### 1. Rust + libwebp-sys (추천)

- 장점: 바인딩 성숙도, 메모리 모델, 이미지 크기, 시스템 콜 표면, 퍼징 계측.
- 단점: tonic 0.x, `libwebp-sys` 단일 소유자, 일부 inline 헬퍼 직접 호출, SIMD 기본 설정.

### 2. Go + 자체 cgo

- 장점: grpc-go가 공식 1.x로 안정적이다. cgo가 C 헤더의 inline 함수를 바로 부른다. libwebp 소스(태그든 커밋이든)를 바꾸기 쉽다.
- 단점: 유지되는 바인딩이 없어 cgo 래퍼와 libwebp 빌드를 모두 직접 관리한다. C 메모리가 GC·GOMEMLIMIT 밖에 있고 protobuf-go가 `bytes`를 복사한다. cgo 포인터 규칙 위반은 런타임 패닉이다(스파이크에서 재현, `runtime.Pinner`로 해결). Go 퍼저는 C 코드를 계측하지 않는다.

### 3. Go + gen2brain/webp (WASM)

- 장점: cgo가 없다. WASM 샌드박스 안에서 libwebp가 돈다.
- 단점: ICC/XMP를 다루지 않는다(EXIF 읽기만). SIMD와 스레드가 꺼져 있다. `image.Image` 수준 API만 있어 요구 범위를 충족하지 못한다.

### 4. Rust + 자체 `-sys`

- 장점: libwebp 소스(태그·커밋·패치)와 빌드 플래그(SIMD)를 완전히 통제한다.
- 단점: 바인딩 생성과 빌드 스크립트를 직접 유지한다. ADR-0008에서 커밋 고정을 택하지 않는 한 필요 없다.

## 참고

- 기준별 상세, 버전, 출처: [docs/research/0003-server-language-rust-vs-go.md](../research/0003-server-language-rust-vs-go.md)
- 스파이크 코드(버릴 코드): [spikes/](../../spikes/)
