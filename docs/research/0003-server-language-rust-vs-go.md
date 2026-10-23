# 0003. 서버 구현 언어: Rust vs Go

- 상태: 초안
- 작성일/확인일: 2026-10-23
- 관련 ADR: ADR-0002

## 요약

- 추천 방향은 **Rust**다. 다만 조건이 붙고, 최종 결정은 사람이 한다. 팀 친숙도(8절)를 확인하지 못했으므로 이 추천은 기술 기준만 반영한다.
- 근거 1(바인딩): `libwebp-sys` 0.14.4는 libwebp 1.6.0(태그 커밋 `4fa21912`)을 벤더링한다. 이 소스를 cargo 빌드 안에서 정적으로 빌드하고, decode/encode/mux/demux/anim 원시 FFI를 모두 노출한다. Go에는 AnimDecoder/AnimEncoder와 Mux 청크(ICC/EXIF/XMP)를 함께 다루면서 유지되는 바인딩이 없다. 그래서 cgo 래퍼를 직접 쓰고 libwebp도 따로 빌드해야 한다 [S6][S12]–[S17].
- 근거 2(메모리): 33 MB급 픽셀 버퍼를 다룰 때 Rust에는 GC가 없다. C 버퍼는 Drop으로 해제할 수 있고, prost `Bytes`를 쓰면 수신 측 복사를 줄일 수 있다. Go에서는 libwebp가 malloc한 메모리가 GC와 GOMEMLIMIT 계산에서 빠지고, protobuf-go가 `bytes` 필드를 복사한다 [S45][S32][S33].
- 근거 3(하드닝): 스파이크에서 Rust musl 정적 이미지는 416 KB, 관찰된 시스템 콜은 18종이었다. Go 정적 이미지는 2.29 MB, 시스템 콜은 33~36종이었다. 단, gRPC 서버가 아니라 단일 실행 CLI로 잰 값이다.
- Go도 실현 가능하다. 두 스파이크 모두 4가지 확인(GetFeatures 선검사, 정지 이미지 디코딩, Mux 청크 읽기, AnimDecoder 3프레임 합성)을 통과했다. Go의 장점은 grpc-go가 공식 1.x(v1.84.0)인 반면 tonic은 0.x(0.14.6)라는 점이다 [S19][S26].
- 어느 언어를 써도 libwebp 안에서 난 segfault는 프로세스 전체를 죽인다. 요청 단위 격리는 언어가 아니라 프로세스 구조(재시작 정책, 워커 프로세스)로 얻어야 한다 [S53][S54].

## libwebp 최신 안정판

| 항목 | 값 | 출처 |
|---|---|---|
| 최신 안정판 | **1.6.0** | [S1][S2] |
| NEWS 날짜 | 2025-06-30 | [S1] |
| 태그 | `v1.6.0` → 커밋 `4fa21912338357f89e4fd51cf2368325b59e9bd9`(커밋 시각 2025-07-08)<br>annotated tag 객체 `b7e29b9d75bd31422b00c2a446d49d7af06c328d`(tagger 날짜 2025-07-09) | [S2] |
| 공식 tarball | `libwebp-1.6.0.tar.gz`, 4,296,070 B<br>sha256 `e4ab7009bf0629fd11982d4c2aa83964cf244cffba7347ecd39019a9e38c4564`(직접 내려받아 계산) | [S3] |
| 이후 릴리스 | 없다. googlesource 태그 목록은 `v1.6.0`에서 끝나고(`-rc` 포함), `libwebp-1.6.1.tar.gz`와 `libwebp-1.7.0.tar.gz`는 404다. | [S2][S3] |

1.6.0은 바이너리 호환 릴리스다. 주요 변경은 다음과 같다 [S1].

- `WebPValidateDecoderConfig` API 추가
- lossless 쪽 x86(AVX2, SSE2) 최적화
- `-mt` 결과가 단일 스레드 결과와 다르던 1.5.0 회귀 수정

**1.3.2 이후 보안 관련 변경**

| 버전(날짜) | NEWS 기록 | 출처 |
|---|---|---|
| 1.3.2 (2023-09-13) | lossless 디코더 보안 수정(CVE-2023-4863) | [S1] |
| 1.4.0 (2024-04-12) | "further security related hardening in libwebp & examples" | [S1] |
| 1.5.0 (2024-12-19) | "further security related hardening and increased fuzzing coverage w/fuzztest" + oss-fuzz 이슈 번호 | [S1] |
| 1.6.0 (2025-06-30) | 보안 항목이 따로 없다("miscellaneous warning, bug & build fixes"). | [S1] |

- NVD에서 CPE `cpe:2.3:a:webmproject:libwebp`로 조회하면 CVE가 15건 나온다. 가장 최근은 CVE-2023-4863(2023-09-12 게시)이다. 1.3.2 이후 libwebp 자체에 배정된 CVE는 NVD에서 찾지 못했다 [S4].
- 키워드 `libwebp`로 찾은 2026년 항목은 libwebp 자체의 결함이 아니다 [S4].
  - CVE-2026-58586: Perl Image::WebP가 구버전 libwebp를 번들한 문제
  - CVE-2026-110635: webp-converter가 CLI 호출에서 명령을 주입당하는 문제

**미릴리스 수정(main)**

2026-10-23 기준 main은 `v1.6.0`보다 362커밋 앞서 있다 [S5]. 그중 우리가 쓸 API와 직접 관련된 수정은 다음과 같다. 보안 영향과 CVE 배정 여부는 미확인이다.

| 커밋 | 날짜 | 내용 | 파일 |
|---|---|---|---|
| `d29e00aa` | 2026-02-05 | anim_decode: `ZeroFillFrameRect` 오버플로 수정 | `src/demux/anim_decode.c` |
| `44b94d53` | 2026-09-29 | `SetFrame()`에서 후보 인코딩 실패 시 double-free 수정 | `src/mux/anim_encode.c` |
| `652631be` | 2026-10-06 | `FrameToFullCanvas()` 오류 경로 double-free 수정 | `src/mux/anim_encode.c` |
| `6391cf3f` | 2026-10-06 | `WebPMuxSetCanvasSize` 면적 검사의 signed overflow 수정 | `src/mux/muxedit.c` |
| `ace1fa52` | 2026-10-09 | row × stride 포인터 오프셋의 32비트 overflow 방지 | dec/enc/dsp 여러 파일 |
| `1844042d` | 2026-10-19 | "Fix invalid bitstream handling when start[0]==255" | `src/dec/vp8_dec.c`, `src/utils/bit_reader_utils.*` |

시사점은 다음과 같다.

- "최신 안정판 고정"과 "알려진 수정 반영"이 충돌한다. 1.6.0 태그를 고정할지 main 특정 커밋을 고정할지는 언어와 별개로 결정해야 한다(ADR 후보).
- 이 결정은 바인딩 선택에 영향을 준다. `libwebp-sys`는 벤더 서브모듈이 `4fa2191`(v1.6.0)로 고정돼 있다. 따라서 main 커밋을 쓰려면 포크하거나 자체 `-sys` 크레이트를 만들어야 한다. Go처럼 libwebp를 직접 빌드하는 방식은 tarball이나 커밋만 바꾸면 된다(1절).

## 비교 기준별 결과 (표 + 항목별 상세)

| # | 기준 | Rust | Go | 판정 |
|---|---|---|---|---|
| 1 | libwebp 바인딩 | `libwebp-sys` 0.14.4: 1.6.0 벤더링, 필요한 API 전부 원시 FFI, cargo가 C까지 빌드 | 요구 API를 모두 덮는 유지 바인딩이 없다. 직접 cgo 래퍼 + libwebp 별도 빌드 | **Rust 우세** |
| 2 | gRPC 스택 | tonic 0.14.6(0.x). 공식 UDS 예제, 수신 기본 4 MB, health/reflection 있음 | grpc-go v1.84.0(1.x, 공식). `unix:` 리졸버 기본 등록, 수신 기본 4 MB, health/reflection 있음 | 둘 다 충분. 안정성은 **Go 약간 우세** |
| 3 | FFI 비용·안전성 | 일반 C 호출과 같은 비용. `extern "C"` 경계를 넘는 panic은 abort | 호출당 수십 ns. 호출 동안 OS 스레드 점유. 포인터 규칙은 런타임 검사 | **Rust 약간 우세**. 둘 다 segfault 격리는 없다 |
| 4 | 메모리·GC | GC 없음. Drop으로 C 버퍼 해제. prost `Bytes` 수신 무복사 | C 메모리는 GC·GOMEMLIMIT 밖. protobuf-go가 `bytes`를 복사 | **Rust 우세** |
| 5 | 정적 링크·크기·크로스 | musl 정적 + scratch 416 KB. arm64 크로스 빌드 성공 | glibc 정적 + distroless/static 2.29 MB. arm64 크로스 빌드 성공(cgo 설정 추가 필요) | **Rust 약간 우세** |
| 6 | 퍼징 | cargo-fuzz(libFuzzer, nightly). clang `CFLAGS`로 링크된 libwebp까지 계측 가능(추정) | `go test -fuzz`. Go 코드만 계측하고 cgo의 C 코드는 계측하지 않음 | **Rust 약간 우세**. libwebp 자체는 OSS-Fuzz가 퍼징 중 |
| 7 | seccomp | 스파이크 18~25종 | 스파이크 33~36종 | **Rust 약간 우세**. 둘 다 Docker 기본 프로파일에서 동작 |
| 8 | 팀 친숙도·유지보수 | 사람에게 확인 필요 | 사람에게 확인 필요 | 미정 |

### 1. libwebp 바인딩 성숙도

**Rust** (버전과 날짜는 crates.io API 기준, 2026-10-23)

| 크레이트 | 최신(날짜) | libwebp 연결 / 버전 | Anim | Mux·Demux(ICC/EXIF/XMP) | 비고 | 출처 |
|---|---|---|---|---|---|---|
| `libwebp-sys` | 0.14.4 (2026-04-29) | 벤더 서브모듈 `4fa2191`(= v1.6.0)을 `cc`로 정적 빌드한다. `system-dylib` 기능을 켜면 pkg-config로 시스템 동적 라이브러리를 쓴다. | 원시 FFI | 원시 FFI(`wrap.h`가 `mux.h`/`demux.h`를 포함) | 바인딩이 미리 생성돼 있다(libclang 불필요, bindgen은 `generate-bindings` 기능에서만). 소유자 1명(NoXF). 안전 래퍼는 없다. | [S6] |
| `libwebp-sys2` | 0.2.0 (2025-02-22) | 기본은 pkg-config로 시스템 라이브러리를 쓴다. `static` 기능, 크로스 빌드, musl에서는 번들 소스(1.5.0)를 빌드한다. | 원시 FFI(`demux` 기능) | 원시 FFI(`mux` 기능) | 버전 게이트 기능이 `1_5`까지만 있어 1.6.0이 반영되지 않았다. | [S7] |
| `webp` | 0.3.1 (2025-08-29) | `libwebp-sys ^0.9.3`에 의존한다. 0.9.x 최신인 0.9.6(2024-09-02)의 벤더 소스는 버전 매크로가 1.3.1이다(CVE-2023-4863 수정 코드 `HuffmanTablesSegment`는 들어 있다). | AnimDecoder/AnimEncoder 안전 래퍼 | Mux 청크 API 래퍼 없음 | 구버전 libwebp에 묶여 있다. | [S8] |
| `webp-animation` | 0.10.0 (2026-04-04) | `libwebp-sys2 0.2`(기능 `0_5`,`0_6`,`1_2`,`demux`,`mux`) | Decoder/Encoder 안전 래퍼 | 없음 | | [S9] |
| `libwebp` | 0.1.2 (2020-03-15) | | | | 갱신 정지 | [S10] |
| `image-webp` (참고) | 0.2.4 (2025-08-27) | libwebp가 아니다. 순수 Rust이고 unsafe가 없다. | 디코딩 지원 | ICC/EXIF/XMP 추출 지원. 인코더는 lossless만 | README는 디코딩 속도를 "libwebp의 70-100%"로 적는다. | [S11] |

`libwebp-sys` 0.14.4 소스를 직접 확인한 결과는 다음과 같다 [S6].

- `build.rs`는 `vendor/src/**/*.c`와 `vendor/sharpyuv/**/*.c`를 전부 `cc`로 컴파일한다. 따라서 decode, encode, mux, demux, anim이 한 정적 라이브러리에 들어간다. cmake는 쓰지 않는다.
- 벤더 소스의 버전 매크로(`DEC_*`, `MUX_*`, `DMUX_*`)는 1.6.0이다. 스파이크 실행에서도 네 라이브러리 버전이 모두 1.6.0으로 나왔다.
- static inline 헬퍼는 일부만 Rust 함수로 제공한다.
  - 제공: `WebPGetFeatures`, `WebPAnimDecoderNew`, `WebPAnimDecoderOptionsInit`, `WebPMuxNew` 등
  - 미제공: `WebPMuxCreate`, `WebPDemux`, `WebPAnimEncoderNew`, `WebPAnimEncoderOptionsInit`
  - 미제공 함수는 `*Internal` 함수에 ABI 상수를 직접 넘겨 호출해야 한다. 스파이크에서는 `WebPMuxCreateInternal`을 썼다.
- `WebPInitConfig`와 `WebPPictureInit` 래퍼가 `WEBP_ENCODER_ABI_VERSION` 대신 `WEBP_DECODER_ABI_VERSION`을 넘긴다. 1.6.0에서는 두 값이 모두 `0x0210`이라 지금은 문제가 없다.
- C enum을 Rust enum으로 생성한다(`default_enum_style` Rust). bindgen 문서는 유효하지 않은 값으로 이런 enum을 만들면 UB라고 적고 있다 [S18]. libwebp가 바인딩에 없는 값을 반환할 가능성에 대비해, 자체 래퍼에서 정수로 받는 처리가 필요하다(추정).
- SIMD 설정은 libwebp 자체 빌드와 다르다.
  - x86_64에서는 `WEBP_HAVE_SSE2`만 기본으로 정의한다.
  - SSE4.1/AVX2는 `sse41`/`avx2` 기능이나 `target-feature`를 줄 때만 켜고, 이때 라이브러리 전체를 그 플래그로 컴파일한다. README는 "will crash on CPUs without it"이라고 경고한다.
  - 반면 libwebp autotools 빌드는 SSE4.1/AVX2 객체를 따로 컴파일한다. Go 스파이크 빌드 로그에서 `libwebpdsp_sse41`과 `libwebpdsp_avx2` 객체가 컴파일되는 것을 확인했다. 성능 차이는 미측정이다.

**Go** (버전과 시각은 proxy.golang.org `@latest` 기준, 2026-10-23)

| 모듈 | 최신(시각) | libwebp 연결 / 버전 | Anim | Mux 청크(ICC/EXIF/XMP) | 비고 | 출처 |
|---|---|---|---|---|---|---|
| `github.com/chai2010/webp` | v1.4.0 (2025-04-06) | cgo. 모듈 안에 libwebp 1.4.0 C 소스를 포함한다. | 없음 | 없음 | 정지 이미지만 다룬다. | [S12] |
| `github.com/kolesa-team/go-webp` | v1.0.5 (2025-03-24) | cgo, 시스템 libwebp(`-lwebp`) | 없음 | 없음 | 정지 이미지만 다룬다. | [S13] |
| `github.com/tidbyt/go-libwebp` | 태그 없음. pseudo `v0.0.0-20230922075150-fb11063b2a6a` (2023-09-22) | cgo, 시스템 `libwebp`/`libwebpmux`/`libwebpdemux` | AnimDecoder/AnimEncoder | 없음 | 2023-09 이후 갱신이 없다. | [S14] |
| `github.com/sizeofint/webpanimation` | pseudo (2021-08-09) | cgo, libwebp 1.2.0 소스 포함 | AnimEncoder만 | 애니메이션 파라미터만 | 갱신 정지 | [S15] |
| `github.com/gen2brain/webp` | v0.6.4 (2026-07-01) | libwebp v1.6.0을 WASM으로 빌드한 뒤 wasm2go로 Go 코드로 바꾼다(cgo 없음). 시스템 libwebp가 있으면 purego로 동적 로드한다. | 디코드(전체 프레임), 인코드 | EXIF는 Go로 파싱해 읽기만 한다. ICC/XMP는 없다. | WASM 빌드는 `WEBP_USE_THREAD=0`, SIMD를 끈 상태다. `image.Image` 수준 API만 있다. | [S16] |
| `golang.org/x/image/webp` (참고) | v0.47.0 (2026-10-09) | 순수 Go | 없음. VP8X 애니메이션 비트만 정의하고 ANIM/ANMF는 처리하지 않는다. | 없음 | 디코더만 있다. | [S17] |

결론은 다음과 같다.

- 어느 언어든 Mux 청크 읽기·쓰기와 Anim 인코딩·디코딩을 아우르는 **안전한 래퍼는 직접 써야 한다**. Rust는 원시 FFI와 C 빌드를 `libwebp-sys`가 맡는다.
- Go는 사용할 만한 바인딩이 없다. 두 가지가 필요하다.
  - cgo 선언: 스파이크처럼 헤더를 include하면 된다.
  - libwebp 빌드: Dockerfile 단계로 빌드하거나, chai2010처럼 C 소스를 패키지 디렉터리에 넣는다.
- cgo는 C 헤더의 static inline 함수(`WebPGetFeatures`, `WebPMuxCreate`, `WebPAnimDecoderNew`)를 바로 부를 수 있다. 이 점은 Rust의 `*Internal` 호출보다 편하다(스파이크에서 확인).
- libwebp 버전을 v1.6.0이 아닌 커밋으로 고정하려면 다음이 필요하다.
  - Rust: `libwebp-sys`를 포크하거나 자체 `-sys` 크레이트를 만든다. `cc` 또는 `cmake` 크레이트로 수십 줄 규모의 `build.rs`가 될 것으로 본다(추정).
  - Go: 빌드 단계의 소스만 바꾸면 된다.

### 2. gRPC 스택

| 항목 | Rust: tonic | Go: grpc-go | 출처 |
|---|---|---|---|
| 최신 | `tonic` 0.14.6 (2026-05-07). 저장소가 `hyperium/tonic`에서 `grpc/grpc-rust`로 옮겨졌다. master는 "preparing breaking changes" 상태이고 릴리스는 `v0.14.x` 브랜치에서 나온다. | `google.golang.org/grpc` v1.84.0 (2026-09-17), `go.mod`의 `go 1.25.0` | [S19][S26] |
| 스트리밍 | unary/server/client/bidi 모두 지원(`examples/src/streaming`) | 지원(생성 코드가 `grpc.ServerStreamingServer[T]` 등 제네릭 스트림 타입을 쓴다) | [S19][S30] |
| UDS 서버 | 공식 예제 `examples/src/uds/server.rs`: `UnixListener::bind` → `UnixListenerStream` → `Server::builder().serve_with_incoming`. 핸들러에서 `UdsConnectInfo`(peer_cred)를 읽을 수 있다. | `net.Listen("unix", path)` + `Serve`. 공식 예제는 abstract 소켓(`examples/features/unix_abstract`)만 있다. | [S20][S28] |
| UDS 클라이언트 | v0.13.0부터 `connect("unix:///path")`를 지원한다. `connect_with_connector` 예제도 있다. | `unix:path`, `unix:///abs`, `unix-abstract:` 대상. 리졸버가 기본 등록돼 있다. | [S20][S27] |
| 메시지 한도 기본값 | 디코딩 4 MB, 인코딩 `usize::MAX`(`DEFAULT_MAX_RECV_MESSAGE_SIZE` / `DEFAULT_MAX_SEND_MESSAGE_SIZE`). 생성된 서버·클라이언트에서 `max_decoding_message_size`/`max_encoding_message_size`로 바꾼다. | 서버 수신 4 MB, 송신 `math.MaxInt32`. `MaxRecvMsgSize`/`MaxSendMsgSize`, 클라이언트는 `MaxCallRecvMsgSize`로 바꾼다. | [S21][S26] |
| health / reflection | `tonic-health` 0.14.6, `tonic-reflection` 0.14.6 (둘 다 2026-05-07) | `google.golang.org/grpc/health`, `.../reflection` | [S22][S29] |
| 코드 생성 | `tonic-prost-build` 0.14.6 + `prost-build` 0.14.4 (2026-06-07). 0.14에서 prost 연동이 `tonic-prost`/`tonic-prost-build`로 분리됐다. `prost-build`는 0.11부터 `protoc`가 필요하다. 대안은 순수 Rust `protox` 0.10.0 (2026-10-07). | `protoc-gen-go` v1.36.12 (2026-08-10), `protoc-gen-go-grpc` v1.6.2 (2026-05-07), buf v1.75.0 (2026-10-21). buf는 내장 컴파일러를 쓰므로 로컬 `protoc`가 필요 없다. | [S23][S24][S25][S30] |
| `bytes` 필드 | `prost_build::Config::bytes`(tonic-prost-build의 `.bytes()`)로 `bytes::Bytes`에 매핑한다. 수신 시 `BytesMut` 분할로 복사가 없다(4절). | `[]byte`. 수신 시 protobuf-go가 복사한다(4절). | [S24][S32][S33] |

- 두 스택 모두 UDS, 청크 스트리밍, 4 MB 수신 한도 조정, health, reflection을 지원한다. 이 프로젝트 요구에 빠지는 기능은 없다.
- 차이는 성숙도 표시에 있다. grpc-go는 gRPC 프로젝트의 공식 1.x 구현이다. tonic은 0.x이고, 저장소 이전과 master의 breaking change 예고가 있다. 같은 저장소에 공식 `grpc` 크레이트 0.9.0(2026-05-28, "Initial Preview Release")도 있다 [S19].
- UDS 소켓 파일 정리는 두 언어 모두 신경 써야 한다.
  - Go `UnixListener.Close`는 `net` 패키지가 만든 소켓 파일만 지운다. 크래시로 남은 파일이 있으면 `Listen`이 실패한다 [S31].
  - tokio `UnixListener`는 Drop에서 파일을 지우지 않는다. 앱이 `bind` 전과 종료 시에 지워야 한다 [S31].

### 3. FFI 비용과 안전성

**Rust**

- 비용
  - Rust 공식 블로그(2015)는 FFI가 "zero-cost abstraction"이며 "function calls between Rust and C have identical performance to C function calls"라고 적었다 [S34].
  - 현재 문서에는 같은 진술이 없다. 지금도 같다고 보는 것은 추정이다.
  - 언어 경계를 넘는 인라이닝은 `-C linker-plugin-lto`(전 객체가 LLVM 툴체인일 때)로만 된다 [S34].
- 안전성
  - `extern` 블록의 함수 호출은 `unsafe`다. 2024 에디션(Rust 1.85)부터는 `unsafe extern`이 필수다 [S35].
  - `C-unwind` ABI는 Rust 1.71.0(2023-07-13)에서 안정화됐다 [S36].
  - Rust 1.81.0(2024-09-05)부터 `extern "C"` 함수 밖으로 빠져나가는 panic은 abort한다 [S37][S38].
  - libwebp는 unwind하지 않는 C 코드다. 따라서 `extern "C"`를 그대로 쓰고, libwebp에 넘기는 Rust 콜백(progress hook, writer)에서는 panic이 새지 않게 해야 한다(추정).
- 동시성
  - tokio는 CPU 바운드나 블로킹 작업에 `spawn_blocking` 또는 rayon 같은 전용 스레드 풀을 권한다. `spawn_blocking` 작업은 abort할 수 없다. [S39]
  - `max_blocking_threads` 기본값은 512다. 동시 디코딩 수는 세마포어 등으로 직접 제한해야 한다 [S39].

**Go (cgo)**

- 비용
  - Go 1.26 릴리스 노트는 "The baseline runtime overhead of cgo calls has been reduced by ~30%"라고 적었다 [S40].
  - 해당 CL 646198의 벤치는 AMD EPYC 7B13에서 `CgoCall` 43.69 ns → 35.83 ns다 [S41].
  - 이슈 19574(2018, go1.10, i7-6600U)에서 측정한 값은 cgo 호출 약 58 ns, Go 호출 1.71 ns다 [S42].
  - 디코딩 한 번이 수 ms 이상이라면 호출당 비용은 무시할 수준이다(추정, 미측정). 행 단위처럼 호출이 잦은 API를 만들 때만 문제가 된다.
- 스레드
  - cgo 호출은 `entersyscall`을 거친다. 호출 동안 그 OS 스레드(M)는 C 코드에 묶인다. P는 sysmon이 회수해 다른 M에 넘긴다 [S43].
  - 시스템 콜·cgo에 묶인 스레드는 GOMAXPROCS에 세지 않는다 [S44].
  - 스레드 수가 `debug.SetMaxThreads` 기본값 10,000을 넘으면 프로그램이 크래시한다 [S45].
  - 따라서 동시 libwebp 호출 수는 Rust와 마찬가지로 직접 제한해야 한다. C 안에 있는 goroutine은 `context`로 취소할 수 없다(추정).
- 포인터 규칙
  - C에 넘기는 Go 메모리 안에 고정되지 않은 Go 포인터가 있으면 안 된다.
  - C는 호출이 끝난 뒤 Go 포인터를 보관하면 안 된다(`runtime.Pinner`로 고정한 경우는 예외).
  - 기본 `GODEBUG=cgocheck=1`이 이를 런타임에 검사한다. 전체 검사는 `GOEXPERIMENT=cgocheck2`로 켠다(Go 1.21부터) [S46][S47].
  - 스파이크에서 Go 메모리를 가리키는 `WebPData`(Go 구조체)를 넘기자 `runtime error: argument of cgo function has Go pointer to unpinned Go pointer` 패닉이 났다. `runtime.Pinner`로 고정하자 통과했다.
  - `WebPAnimDecoderNew`와 `WebPMuxCreate(copy_data=0)`은 입력 포인터를 호출 이후에도 쓴다. 따라서 입력을 `C.CBytes`로 C 힙에 복사하거나, 디코더 수명 동안 Pinner로 고정해야 한다.

**공통: 크래시**

- Go 런타임은 C 코드 실행 중 받은 동기 시그널을 복구할 수 없다고 본다("we're running C code. Either way we cannot recover"). 이 경우 `_SigThrow`로 프로세스를 죽인다 [S53].
- Rust std의 SIGSEGV 핸들러는 스택 오버플로 감지용뿐이다. 그 외의 경우에는 기본 동작으로 되돌려 시그널을 다시 받게 한다 [S54].
- 즉 두 언어 모두 libwebp 결함 하나가 진행 중인 모든 요청과 함께 사이드카를 죽인다. 대응은 언어와 무관하다.
  - 재시작 정책
  - 클라이언트의 `UNAVAILABLE` 재시도
  - 필요하면 요청별 워커 프로세스(추정)

### 4. 메모리와 GC

**Go**

- 할당과 GC
  - 32 KB를 넘는 객체는 힙에서 바로 할당한다 [S59].
  - GC는 non-moving이다. 포인터가 없는 값은 따로 관리돼 스캔 비용이 적다 [S49].
  - `make([]byte, n)`은 0으로 초기화된다. 따라서 33 MB 버퍼를 새로 만들 때마다 초기화 비용과 GC 페이싱 부담이 생긴다(추정). `sync.Pool`로 재사용할 수 있다. 다만 풀의 항목은 언제든 회수될 수 있고, 포인터 타입(`*[]byte`)을 넣어야 한다 [S52].
- 메모리 한도
  - **libwebp가 malloc한 메모리는 GC와 GOMEMLIMIT 계산에서 빠진다.** `debug.SetMemoryLimit` 문서는 제외 대상에 "memory allocated by C code"를 명시한다 [S45][S50].
  - GC 가이드는 컨테이너 메모리 한도가 있을 때 memory limit을 쓰되, 런타임이 모르는 메모리를 위해 5~10% 여유를 두라고 한다. C 라이브러리가 일시적으로 메모리를 많이 쓰는 cgo 프로그램에서는 한도를 실시간으로 조정해도 된다고 한다 [S49].
  - GOMEMLIMIT 기본값은 `math.MaxInt64`다 [S45]. cgroup 메모리 한도를 자동으로 반영한다는 기록은 Go 1.25~1.27 릴리스 노트에서 찾지 못했다(미확인). 따라서 명시적으로 설정해야 한다.
- CPU: Go 1.25부터 GOMAXPROCS가 cgroup CPU 한도를 반영한다(올림, 최소 2) [S44][S51]. `go.mod`의 go 지시어가 1.25 이상이어야 한다. 스파이크를 `--cpus 1`로 실행했을 때 `GOMAXPROCS=2`가 나왔다.
- gRPC 경로의 복사
  - protobuf-go는 언마샬할 때 `bytes` 필드를 복사한다(`consumeBytes`의 `append(emptyBuf[:], v...)`) [S32].
  - grpc-go는 v1.66.0부터 `CodecV2`/`mem` 패키지로 버퍼를 재사용하지만, `mem`은 아직 EXPERIMENTAL이다 [S31].
  - `SendMsg` 이후에는 메시지를 수정하면 안 된다. 따라서 청크 버퍼를 Send 사이에 재사용하는 데 제약이 있다 [S31].
- C 메모리 다루기: C 메모리는 `unsafe.Slice`로 복사 없이 볼 수 있다. 다만 GC가 관여하지 않으므로 해제 시점은 직접 관리해야 한다 [S48].

**Rust**

- GC가 없다. libwebp 버퍼를 소유하는 타입에 `Drop`(`WebPFree`)을 구현하면 해제가 수명에 묶인다(일반적인 RAII, 추정).
- `prost_build::Config::bytes`로 `bytes::Bytes`를 쓰면 수신 경로에서 복사가 없다. tonic의 `DecodeBuf::copy_to_bytes`가 `BytesMut` 분할(`split_to().freeze()`)이기 때문이다. 이 결론은 소스를 읽고 추론한 것이다 [S33].
- 송신 시에는 청크마다 `EncodeBuf`로 한 번 복사된다(소스 기반 추정).
- tokio 워커 스레드 수의 기본값은 `available_parallelism()`이고, Linux에서는 cgroup 쿼터를 반영한다 [S39].

**공통**

- libwebp에는 호출자 버퍼로 디코딩하는 API가 있다. `WebPDecodeRGBAInto`와 `WebPDecBuffer.is_external_memory`다 [S60]. 두 언어 모두 이 API로 "디코딩 → 청크 송신" 사이의 복사를 줄일 수 있다.
- 컨테이너 메모리 한도는 두 언어 모두 프로세스 전체(RSS)에 걸린다. 차이는 Go에서만 GC 목표와 실제 사용량이 C 메모리만큼 어긋난다는 점이다.

### 5. 정적 링크·이미지 크기·크로스 컴파일

스파이크 측정(x86_64, 2026-10-23):

| 변형 | 링크 | 실행 이미지 | 바이너리 크기 | 이미지 content size | 기준 이미지만의 content size |
|---|---|---|---|---|---|
| Rust glibc | glibc 동적(libwebp 정적) | `gcr.io/distroless/cc-debian13:nonroot` | 750,800 B | 11.05 MB | 10.69 MB |
| Rust musl | static-pie | `scratch` (USER 65532) | 844,744 B | **0.42 MB** (415,669 B) | 0 |
| Go 동적 | glibc 동적(libwebp 정적) | `gcr.io/distroless/base-nossl-debian13:nonroot` | 2,141,480 B | 6.96 MB | 5.94 MB |
| Go 정적 | glibc 완전 정적(`-linkmode external -extldflags "-static"`) | `gcr.io/distroless/static-debian13:nonroot` | 3,032,176 B | **2.29 MB** | 0.86 MB |

- content size는 `docker image inspect --format '{{.Size}}'` 값이다. Docker 29의 containerd 이미지 저장소에서 `docker image ls`의 "CONTENT SIZE" 열과 같고, 압축된 레이어 크기로 보인다(추정). 같은 표의 "DISK USAGE"(압축 해제 포함)는 Rust musl 1.27 MB, Go 정적 11.1 MB였다.
- Go glibc 완전 정적 링크는 경고 없이 성공했다. 스파이크 코드가 DNS나 사용자 조회를 쓰지 않기 때문으로 보인다(추정). gRPC 서버에서 이름 해석을 쓰면 결과가 달라질 수 있다.
- arm64 크로스 빌드: 두 스파이크 모두 amd64 호스트에서 Debian 크로스 gcc(`gcc-aarch64-linux-gnu`)로 성공했다. 결과물은 `ELF 64-bit LSB ... ARM aarch64`이고, 실행은 검증하지 않았다(호스트에 QEMU binfmt 없음).
  - Rust: `rustup target add aarch64-unknown-linux-gnu`를 하고 `CARGO_TARGET_..._LINKER`와 `CC_aarch64_unknown_linux_gnu`를 설정하면 `cc` 크레이트가 libwebp까지 크로스 빌드한다. 빌드 10초.
  - Go: `CGO_ENABLED=1 GOARCH=arm64 CC=aarch64-linux-gnu-gcc`를 명시해야 한다. libwebp는 `./configure --host=aarch64-linux-gnu`로 따로 크로스 빌드해야 한다. 빌드 15초.

문서로 확인한 내용:

- Rust
  - `x86_64-unknown-linux-musl`과 `aarch64-unknown-linux-musl`은 모두 "Tier 2 with Host Tools"(musl 1.2.5)다 [S61].
  - 두 타깃 사양 모두 `crt_static_default = true`다. 스파이크의 musl 바이너리도 별도 설정 없이 static-pie로 나왔다 [S61].
  - `cc` 1.9.0은 `CC_<target>`, `CFLAGS_<target>`, `CROSS_COMPILE` 등으로 크로스 컴파일러를 고른다. `zig cc`도 Clang 계열로 인식한다 [S62].
  - 크로스 도구
    - cross-rs의 마지막 태그 릴리스는 v0.2.5(2023-02-04)다. README는 git 설치를 안내하고 main은 활동 중이다 [S63].
    - cargo-zigbuild v0.24.0(2026-10-21)은 `cc`가 쓰는 `CC_<target>`도 zig로 설정한다. glibc `+crt-static`은 지원하지 않으니 musl 타깃을 쓰라고 안내한다 [S64].
  - musl malloc
    - ripgrep 소스는 musl 할당자가 "appears to be substantially worse"라며 musl에서 jemalloc으로 바꾼다. 수치는 없다 [S65].
    - mimalloc(`override`)과 tikv-jemallocator(`unprefixed_malloc_on_supported_platforms`)에는 C 코드의 malloc까지 대체하는 기능이 있다 [S65].
    - `#[global_allocator]`만 쓰면 libwebp의 malloc은 musl malloc에 남는다(추정). musl 빌드를 택하면 할당자 성능을 벤치해야 한다.
- Go
  - cmd/cgo 문서: "It is disabled by default when cross-compiling as well as when the CC environment variable is unset and the default C compiler ... cannot be found". 따라서 크로스 컴파일에서는 `CC`를 지정해야 한다 [S46].
  - `zig cc`를 cgo 크로스 컴파일러로 쓰는 방법은 커뮤니티 관행이다. Go나 Zig 공식 문서에서는 찾지 못했다(미확인) [S64].
  - glibc 정적 링크
    - glibc는 NSS, gconv, IDN 등에서 여전히 dlopen을 쓴다. 정적 dlopen은 2.27부터 deprecated다 [S67].
    - 정적 Go 바이너리에는 `netgo`/`osusergo` 빌드 태그로 libc 리졸버와 사용자 조회를 피하는 방법이 있다 [S67].
    - `-linkmode external -extldflags "-static"`은 관행이다. 이를 권장하는 공식 Go 문서는 찾지 못했다(미확인).
  - Docker 공식 golang Alpine 이미지는 "not officially supported by the Go project"로 표시돼 있다 [S68].
- 공통
  - Docker 멀티 플랫폼 빌드에는 세 가지 전략이 있다: QEMU 에뮬레이션, 네이티브 노드, 멀티 스테이지 크로스 컴파일. 문서는 QEMU가 컴파일 같은 계산 작업에서 "much slower"라고 경고한다 [S66].
  - distroless의 현재 이미지는 Debian 13 기반이다. 구성은 다음과 같다 [S58].
    - `static`: ca-certificates, tzdata, passwd
    - `base-nossl`: static + glibc
    - `base`: base-nossl + libssl
    - `cc`: base + libgcc1
  - distroless의 nonroot UID는 65532다. debian12 빌드는 2026-09-10 커밋으로 제거됐다 [S58].

### 6. 퍼징

- Rust
  - cargo-fuzz 0.13.2(2026-06-09)는 libFuzzer만 지원하고 nightly 컴파일러가 필요하다 [S69].
  - cargo-fuzz는 `RUSTFLAGS`로 Rust 코드에만 sanitizer coverage를 넣는다. `cc`로 빌드한 C 코드는 기본으로 계측하지 않는다(소스 기반 추정) [S69].
  - clang으로 `CFLAGS=-fsanitize=fuzzer-no-link`를 주면 libwebp도 계측할 수 있다. LLVM 문서가 이 플래그를 "링크 없이 계측만 하는 방법"으로 설명한다 [S70]. OSS-Fuzz 기본 이미지도 이 플래그를 `CFLAGS`에 넣는다 [S73]. 다만 `libwebp-sys`의 `cc` 빌드가 이를 그대로 받는지는 실행해 보지 않았다(추정).
  - LLVM 문서는 libFuzzer 원저자들이 Centipede로 옮겨 갔고, libFuzzer는 중요한 버그만 고치는 상태라고 적는다 [S70].
- Go
  - 네이티브 퍼징은 Go 1.18부터 표준 도구에 들어 있다. 커버리지 계측은 AMD64/ARM64에서만 된다. 인자 타입은 string, []byte, 정수, 실수, bool로 제한된다 [S71].
  - 계측은 Go 컴파일러 플래그(`-d=libfuzzer`)로 Go 패키지에만 들어간다. cgo로 컴파일한 C 코드는 커버리지 피드백이 없다. 이는 소스 기반 추정이고 공식 문서 문장은 찾지 못했다. 열린 PR golang/go#77408도 네이티브 Go 퍼징에 libFuzzer 계측 지원이 없다고 적고 있다 [S71].
- libwebp 자체
  - OSS-Fuzz 프로젝트 `projects/libwebp`가 있다(언어 c++, 엔진 libfuzzer, sanitizer address/undefined/memory, main_repo `chromium.googlesource.com/webm/libwebp`) [S72].
  - 퍼저는 libwebp 저장소 `tests/fuzzer/`에 있다(`animdecoder_fuzzer.cc`, `animencoder_fuzzer.cc`, `mux_demux_api_fuzzer.cc`, `advanced_api_fuzzer.cc`, `dec_fuzzer.cc` 등 14개 `*_fuzzer.cc`) [S72].
  - 1.5.0 NEWS도 fuzztest로 퍼징 범위를 늘렸다고 적는다 [S1].
- OSS-Fuzz는 Rust(cargo fuzz, libfuzzer + address)와 Go(네이티브 퍼저를 libFuzzer 바이너리로 빌드)를 모두 지원한다 [S73].
- 시사점(추정)
  - libwebp 내부 퍼징은 OSS-Fuzz에 맡긴다. 우리 퍼징 대상은 서버 쪽 경계다: 청크 재조립, 크기 상한, 메타데이터 경로, 오류 매핑.
  - Rust는 같은 퍼저 안에서 libwebp까지 커버리지를 얻을 여지가 있다. 순수 Rust `image-webp`를 차분 퍼징(differential fuzzing) 오라클로 쓸 수도 있다.
  - Go는 C 쪽을 블랙박스로 둔 채 Go 코드만 퍼징하게 된다.

### 7. seccomp

스파이크 바이너리를 `strace -f`로 실행해 관찰한 시스템 콜 종류다(`still.webp`, `anim.webp` 처리, 1회 실행). strace를 쓰려고 `debian:trixie-20261020-slim`(`sha256:62bd423aeee912639ca8ee681c2b762cb3e25137e0a92b20be455898a9f034ad`)에서 `--cap-add SYS_PTRACE --security-opt seccomp=unconfined`로 실행했다. 따라서 기본 프로파일이 막는 `clone3`도 그대로 보인다.

| 바이너리 | 종류 수 | 목록 |
|---|---|---|
| Rust musl 정적 | 18 | arch_prctl brk close execve exit_group fcntl fstat mmap mprotect munmap open poll read rt_sigaction rt_sigprocmask set_tid_address sigaltstack write |
| Rust glibc | 25 | access arch_prctl brk close execve exit_group fstat getrandom gettid mmap mprotect munmap openat poll pread64 prlimit64 read rseq rt_sigaction sched_getaffinity set_robust_list set_tid_address sigaltstack statx write |
| Go glibc 동적 | 33 | access arch_prctl brk clone3 close epoll_create1 epoll_ctl eventfd2 execve exit_group fcntl fstat futex getrandom gettid madvise mmap mprotect munmap nanosleep openat prctl pread64 prlimit64 read rseq rt_sigaction rt_sigprocmask sched_getaffinity set_robust_list set_tid_address sigaltstack write |
| Go glibc 정적 | 36 | 위 목록 + getpid readlinkat rt_sigreturn tgkill − access |

- 이 값은 단일 실행 CLI 기준이다. gRPC 서버는 소켓(`socket`/`bind`/`listen`/`accept4`), epoll, 스레드 생성(Rust/tokio도 `clone3`/`futex`) 등이 더해진다(추정). 서버 단계에서 다시 기록해야 한다.
- Go는 런타임이 스레드(`clone3`), `futex`, netpoller(`epoll_*`, `eventfd2`)를 시작 시점부터 쓴다. 정적 빌드에서는 시그널 기반 선점으로 보이는 `tgkill`/`rt_sigreturn`도 관찰됐다(타이밍에 따라 달라질 수 있다).
- 네 바이너리 모두 Docker 기본 seccomp 프로파일과 `--read-only --network none --cap-drop ALL --security-opt no-new-privileges --memory 256m --cpus 1`, nonroot 사용자(UID 65532)에서 정상 종료했다.

문서와 소스로 확인한 내용:

- Docker 기본 프로파일
  - 문서는 "disables around 44 system calls out of 300+"이고 allowlist 방식이라고 적는다. io_uring 계열은 막는다 [S74].
  - `default.json`의 `defaultAction`은 `SCMP_ACT_ERRNO`, `defaultErrnoRet`은 1(EPERM)이다 [S74].
  - `clone3`는 `CAP_SYS_ADMIN`이 없으면 errno 38(ENOSYS)을 돌려준다. glibc가 `clone`으로 폴백하게 하려는 것이다(moby PR #42681) [S74].
- Go 런타임
  - 순수 Go 부분은 libc 없이 시스템 콜을 직접 호출한다(`runtime/sys_linux_amd64.s`). netpoller는 `epoll_*`과 `eventfd2`를 쓴다 [S75].
  - cgo를 쓰면 스레드를 `pthread_create`로 만든다 [S75].
  - Go 1.14부터 goroutine 비동기 선점에 시그널(`SIGURG`)을 쓴다. 그래서 `tgkill`, `rt_sigreturn`, `sigaltstack`이 필요하다 [S75].
  - `faccessat2`, pidfd 계열은 ENOSYS나 EPERM을 받으면 폴백하는 코드가 있다 [S75].
- Rust/tokio
  - tokio(mio)는 Linux에서 epoll과 eventfd를 쓴다. io_uring은 `tokio_unstable` 기능으로만 켜진다 [S76].
  - Rust std는 seccomp가 `statx`를 막아 EPERM이 나오는 경우를 대비해 폴백한다 [S76].
  - glibc 2.35부터 스레드를 `rseq`에 자동 등록한다. 스파이크 glibc 바이너리에서도 `rseq`가 보였다 [S77].
- 프로파일 생성 도구 [S78]
  - Kubernetes Security Profiles Operator v1.1.1(2026-10-05): audit 로그나 eBPF로 기록한다. `spoc` CLI는 Kubernetes 밖에서도 쓸 수 있다.
  - Inspektor Gadget v0.57.0(2026-10-05): 이미지 가젯 `advise_seccomp`. 예전의 내장 `advise seccomp-profile`은 없어졌다.
  - containers/oci-seccomp-bpf-hook v1.3.0(2026-03-18): podman 어노테이션으로 실행 중 시스템 콜을 기록한다.
  - Kubernetes는 `seccompProfile.type: RuntimeDefault`/`Localhost`를 지원한다. kubelet `--seccomp-default`(v1.27부터 stable)를 켜지 않으면 기본값은 Unconfined다.
  - strace 기반 수동 작성은 관행이다. 공식 문서는 인용하지 않았다.
- 시사점(추정)
  - 언어와 무관하게 서버 단계에서 실제 RPC 부하를 걸고 위 도구로 프로파일을 기록하는 것이 현실적이다.
  - 직접 프로파일을 쓸 때는 거부 시스템 콜에 EPERM 대신 ENOSYS를 돌려줘야 glibc/Go/Rust std의 폴백이 동작한다(특히 `clone3`). x86_64와 aarch64를 모두 넣어야 한다.
  - Rust 쪽 목록이 더 짧았지만, tokio 서버가 되면 차이가 줄어들 수 있다.

### 8. 팀 친숙도와 유지보수성 (사람에게 확인 필요)

이 항목은 추측하지 않는다. 다음 질문에 대한 답이 필요하다.

1. 서버를 유지보수할 사람(들)의 Rust와 Go 실무 경험은 각각 어느 정도인가? (운영 중인 서비스, 코드 리뷰 가능 여부)
2. `unsafe` Rust 또는 cgo로 C 라이브러리 래퍼를 작성·리뷰해 본 사람이 있는가? 메모리 소유권과 수명 규칙을 리뷰할 사람은 누구인가?
3. 조직의 기존 빌드·배포 파이프라인(CI 이미지, 사내 레지스트리, SBOM·취약점 스캐너)이 cargo와 Go 모듈 중 어느 쪽을 이미 지원하는가?
4. 프로토타입 이후 제품화할 때 서버를 맡을 팀이 정해져 있는가? 그 팀의 주 언어는 무엇인가?
5. 사내에 tonic 또는 grpc-go 운영 경험(관측성, 인터셉터, 부하 테스트)이 있는가?
6. 의존성 정책상 0.x 버전 크레이트(tonic 0.14, libwebp-sys 0.14)를 써도 되는가? 단일 관리자 크레이트(libwebp-sys, 소유자 1명)를 포크해 관리할 의향이 있는가?

## 스파이크 결과

버릴 코드다. ADR-0002 결정 후 `spikes/`와 함께 삭제한다.

**환경과 고정 버전**

| 항목 | 값 |
|---|---|
| 호스트 | Linux x86_64, 논리 CPU 32개, Docker 29.1.3 (로컬 Rust/Go/gcc/protoc 없음) |
| Rust 빌드 이미지 | `rust:1.99.1-slim-trixie@sha256:aab42391a986424e22d6a3ec14f37bc6dd3dacff4d457ca7cccd781e02297cc6`<br>`rust:1.99.1-alpine3.24@sha256:b1d5dd5a22a09404d3e798c0cf051b689077360374eb505fa5bb93c479693d87` |
| Go 빌드 이미지 | `golang:1.27.3-trixie@sha256:fbb7ae40b62ff8afe31662df570d39b4f5052ac32b90dae8e60d7657a20a74f4` |
| 실행 이미지 | `gcr.io/distroless/cc-debian13:nonroot@sha256:83b9b50586253821cf63ffcec87d3d94265a093ad30da232039af962dd8f4edf`<br>`gcr.io/distroless/base-nossl-debian13:nonroot@sha256:6d92083da644c5fceb58fb1dc50e88b3c1aa0d8b6099678223fbb801bd72962b`<br>`gcr.io/distroless/static-debian13:nonroot@sha256:d62067879de890a1def9da0e7766b7d20810a5e5555cef8506b752437d7a3e88`<br>`scratch` |
| Rust 의존성 | `libwebp-sys` 0.14.4(`=` 고정), `cc` 1.9.0 등 11개 패키지(`Cargo.lock`) |
| Go 의존성 | 외부 모듈 없음(cgo만 사용). 따라서 `go.sum`이 없다. libwebp는 공식 tarball 1.6.0을 sha256 검증 후 `./configure --disable-shared --enable-static --enable-libwebpmux --enable-libwebpdemux`로 빌드했다. |
| 툴체인 | Rust 1.99.1(2026-10-15) [S56], Go 1.27.3(2026-10-19) [S55]. 빌드 이미지 태그는 Docker Hub에서 2026-10-22 갱신분을 골라 digest로 고정했다 [S57]. |

**결과**

| 스파이크 | 빌드 | 실행 | 링크된 libwebp | 빌드 시간(`--no-cache`, 기준 이미지 캐시됨) |
|---|---|---|---|---|
| `rust-webp-decode` (glibc) | 성공 | 성공 | decoder/encoder/demux/mux 모두 1.6.0 | 약 6초(그중 `cargo build` 2.47초, 크레이트 내려받기 포함) |
| `rust-webp-decode` (`--target musl`) | 성공 | 성공 | 1.6.0 | 약 6초(`cargo build` 4.26초) |
| `go-webp-decode` (동적 + `--target static`) | 성공 | 성공 | 1.6.0 | 약 11초(libwebp configure+make 7.9초, `go build` 두 번 2.3초) |
| 두 스파이크 `Dockerfile.cross-arm64` | 성공 | 미검증 | - | Rust 10초, Go 15초(apt 설치 포함) |

빌드 시간은 32코어에서 1회 측정한 값이다. 비교 지표로 쓰기엔 부족하다.

공통 출력(Rust, `still.webp`·`anim.webp`):

```text
libwebp linked: decoder=1.6.0 encoder=1.6.0 demux=1.6.0 mux=1.6.0
== still: /data/still.webp
features: width=4 height=3 has_alpha=1 has_animation=0 format=2
guard: ok (<= 100000000 px)
decoded: 4x3 first_pixel_rgba=(18, 52, 86, 120) bytes=48
mux chunk "ICCP": 28 bytes
mux chunk "EXIF": 16 bytes
mux chunk "XMP ": 37 bytes
== anim: /data/anim.webp
features: width=4 height=3 has_alpha=1 has_animation=1
anim: canvas=4x3 frame_count=3 loop_count=0 bgcolor=0xFFFFFFFF
  frame 1: timestamp_ms=100 first_pixel_rgba=(255, 0, 0, 255)
  frame 2: timestamp_ms=200 first_pixel_rgba=(0, 255, 0, 255)
  frame 3: timestamp_ms=300 first_pixel_rgba=(0, 0, 255, 128)
anim: decoded_frames=3
```

Go는 위와 같은 줄에 다음 줄이 더 나온다.

```text
go: go1.27.3 GOMAXPROCS=2
== cgo pointer rule demo
unpinned: panic: runtime error: argument of cgo function has Go pointer to unpinned Go pointer
pinned (runtime.Pinner): ok
```

추가 확인:

- **상한 검사**: `bomb_16384.webp`(41 B, VP8L 헤더만 16384x16384로 선언)를 넣었다. 두 스파이크 모두 `WebPGetFeatures`가 크기를 돌려준 뒤 `too large: 16384x16384 = 268435456 px > 100000000`로 디코딩 전에 거부했다(종료 코드 1).
- `anim.webp`를 정지 이미지 경로(`WebPDecodeRGBA`)에 넣으면 디코딩에 실패했다(`has_animation=1`, `format=0`). 애니메이션은 반드시 AnimDecoder 경로로 보내야 한다.
- 첫 픽셀 (18, 52, 86, 120) = (0x12, 0x34, 0x56, 0x78)은 입력 PNG와 같다. 무손실·`-exact` 인코딩이므로 정확히 일치해야 맞다.

마찰 기록:

- Rust
  - bindgen이나 libclang은 필요 없었다. 바인딩이 크레이트에 미리 들어 있다.
  - `WebPMuxCreate`가 바인딩에 없어 `WebPMuxCreateInternal(..., WEBP_MUX_ABI_VERSION)`을 직접 불렀다.
  - C enum이 Rust enum이라 상태 비교는 편하다. 다만 1절의 UB 주의가 있다.
  - musl 빌드에서는 `apk add musl-dev`를 넣었다. 필요 여부는 확인하지 않았다.
- Go
  - libwebp 빌드 단계를 직접 작성해야 했다(tarball 검증, configure 옵션, 정적 라이브러리 링크 순서 `-lwebpmux -lwebpdemux -lwebp -lsharpyuv -lm -lpthread`).
  - cgo가 static inline 함수를 바로 호출할 수 있어 Rust보다 선언 작업이 적었다.
  - Anim/Mux 입력을 C 힙으로 복사(`C.CBytes`)했다. 입력은 압축 데이터라 디코딩 결과보다 훨씬 작으므로 비용은 작다(추정).
- 공통: 성능(처리량, 지연), 최대 메모리, gRPC 연동은 측정하지 않았다.

## 미확인 항목

- libwebp main의 미릴리스 수정(1844042d 등)이 보안 영향을 갖는지, CVE가 배정될지, 다음 릴리스(1.6.1/1.7.0) 일정.
- `libwebp-sys`의 SIMD 빌드 방식(SSE4.1/AVX2 미포함)이 실제 디코딩·인코딩 성능에 주는 영향. 벤치가 필요하다.
- cgo 호출 오버헤드와 Go GC가 이 워크로드(4K 프레임, 애니메이션)의 지연·처리량에 주는 영향. 미측정이다.
- Rust 송신 경로의 복사 횟수와 Go `CodecV2` 경로의 실제 복사 횟수. 소스를 읽어 추정만 했다.
- Go가 cgroup 메모리 한도를 GOMEMLIMIT에 자동 반영하지 않는다는 점. 릴리스 노트에서 찾지 못한 것일 뿐, 부재를 확인하지는 않았다.
- gRPC 서버 형태에서의 시스템 콜 목록(스파이크는 CLI).
- cargo-fuzz에서 `CFLAGS=-fsanitize=fuzzer-no-link`(clang)로 `libwebp-sys`의 C 코드까지 계측되는지. 실행해 보지 않았다.
- musl malloc이 libwebp 디코딩·인코딩 성능에 주는 영향. 수치가 있는 출처를 찾지 못했다.
- arm64 바이너리 실행(크로스 빌드만 확인).
- tonic 1.0 또는 공식 `grpc` 크레이트로의 이행 시점.
- 팀 친숙도(8절).

## 출처

- [S1] libwebp `NEWS` (main) — https://chromium.googlesource.com/webm/libwebp/+/refs/heads/main/NEWS , 미러 https://github.com/webmproject/libwebp/blob/main/NEWS (확인 2026-10-23)
- [S2] libwebp 태그 — https://chromium.googlesource.com/webm/libwebp/+refs (태그 JSON `?format=JSON`), https://chromium.googlesource.com/webm/libwebp/+/refs/tags/v1.6.0 , https://github.com/webmproject/libwebp/tree/v1.6.0 ; tag 객체 `b7e29b9d75bd31422b00c2a446d49d7af06c328d`, 커밋 `4fa21912338357f89e4fd51cf2368325b59e9bd9` (확인 2026-10-23)
- [S3] libwebp 릴리스 다운로드 — https://storage.googleapis.com/downloads.webmproject.org/releases/webp/index.html , https://storage.googleapis.com/downloads.webmproject.org/releases/webp/libwebp-1.6.0.tar.gz (sha256 직접 계산) (확인 2026-10-23)
- [S4] NVD CVE API — https://services.nvd.nist.gov/rest/json/cves/2.0?virtualMatchString=cpe:2.3:a:webmproject:libwebp (15건), https://services.nvd.nist.gov/rest/json/cves/2.0?keywordSearch=libwebp (18건) (확인 2026-10-23)
- [S5] libwebp `v1.6.0...main` 비교 — https://github.com/webmproject/libwebp/compare/v1.6.0...main (ahead_by 362); 커밋 https://github.com/webmproject/libwebp/commit/d29e00aa708243ff9945989a0e4e3c8cf6dbf152 , .../44b94d53dbf07405d711f8c153c59d119893bedd , .../652631be0333123a69dab036f5586894fc743ea6 , .../6391cf3f2386f214d611864da952be94ce60d040 , .../ace1fa526b58657621686f41f9763509f8718baf , .../1844042d1ca751e62e61df7617dba19c98588339 (확인 2026-10-23)
- [S6] `libwebp-sys` 0.14.4 — https://crates.io/crates/libwebp-sys , 소스 https://static.crates.io/crates/libwebp-sys/libwebp-sys-0.14.4.crate (`build.rs`, `wrap.h`, `src/lib.rs`, `src/ffi.rs`, `vendor/src/dec/vp8i_dec.h` L38-40, `vendor/src/mux/muxi.h` L34-36, `vendor/src/demux/demux.c` L29-31), 저장소 https://github.com/NoXF/libwebp-sys (`vendor @ 4fa2191`), 소유자 API https://crates.io/api/v1/crates/libwebp-sys/owners (확인 2026-10-23)
- [S7] `libwebp-sys2` 0.2.0 — https://crates.io/crates/libwebp-sys2 , 소스 `build.rs`, `c_src/NEWS`, `c_src/src/dec/vp8i_dec.h` L34-36 (https://static.crates.io/crates/libwebp-sys2/libwebp-sys2-0.2.0.crate) (확인 2026-10-23)
- [S8] `webp` 0.3.1 — https://crates.io/crates/webp , 소스 `Cargo.toml`(libwebp-sys = "0.9.3"), `src/animation_decoder.rs`, `src/animation_encoder.rs`; `libwebp-sys` 0.9.6 `vendor/src/dec/vp8i_dec.h` L33-35, `vendor/src/utils/huffman_utils.h` (https://static.crates.io/crates/libwebp-sys/libwebp-sys-0.9.6.crate) (확인 2026-10-23)
- [S9] `webp-animation` 0.10.0 — https://crates.io/crates/webp-animation , 소스 `Cargo.toml` (확인 2026-10-23)
- [S10] `libwebp` 0.1.2 — https://crates.io/crates/libwebp (확인 2026-10-23)
- [S11] `image-webp` 0.2.4 — https://crates.io/crates/image-webp , `README.md` (https://static.crates.io/crates/image-webp/image-webp-0.2.4.crate) (확인 2026-10-23)
- [S12] `github.com/chai2010/webp` v1.4.0 — https://proxy.golang.org/github.com/chai2010/webp/@latest , https://pkg.go.dev/github.com/chai2010/webp , 모듈 zip의 `internal/libwebp-1.4.0/`, `capi.go` (확인 2026-10-23)
- [S13] `github.com/kolesa-team/go-webp` v1.0.5 — https://proxy.golang.org/github.com/kolesa-team/go-webp/@latest , https://pkg.go.dev/github.com/kolesa-team/go-webp (확인 2026-10-23)
- [S14] `github.com/tidbyt/go-libwebp` — https://proxy.golang.org/github.com/tidbyt/go-libwebp/@latest , https://pkg.go.dev/github.com/tidbyt/go-libwebp , 모듈 zip의 `webp/anim_decode.go`, `webp/anim_encode.go` (확인 2026-10-23)
- [S15] `github.com/sizeofint/webpanimation` — https://proxy.golang.org/github.com/sizeofint/webpanimation/@latest , 모듈 zip의 `capi.go`, `dec_vp8i_dec.h` L33-35 (확인 2026-10-23)
- [S16] `github.com/gen2brain/webp` v0.6.4 — https://proxy.golang.org/github.com/gen2brain/webp/@latest , https://pkg.go.dev/github.com/gen2brain/webp , 모듈 zip의 `README.md`, `lib/Makefile`(`LIBWEBP_VERSION = v1.6.0`), `lib/webp.c`, `exif.go` (확인 2026-10-23)
- [S17] `golang.org/x/image/webp` v0.47.0 — https://pkg.go.dev/golang.org/x/image/webp , 모듈 zip의 `webp/decode.go` L163-169 (확인 2026-10-23)
- [S18] bindgen `EnumVariation` (0.73.2) — https://docs.rs/bindgen/latest/bindgen/enum.EnumVariation.html (확인 2026-10-23)
- [S19] tonic 0.14.6 — https://crates.io/crates/tonic , https://github.com/grpc/grpc-rust/releases/tag/tonic-v0.14.6 , https://github.com/grpc/grpc-rust/blob/master/README.md , https://github.com/grpc/grpc-rust/releases/tag/grpc-v0.9.0 (확인 2026-10-23)
- [S20] tonic UDS 예제 — https://github.com/grpc/grpc-rust/blob/tonic-v0.14.6/examples/src/uds/server.rs , .../client_standard.rs , .../client_with_connector.rs , https://github.com/grpc/grpc-rust/releases/tag/v0.13.0 (확인 2026-10-23)
- [S21] tonic 메시지 한도 — https://github.com/grpc/grpc-rust/blob/tonic-v0.14.6/tonic/src/codec/mod.rs (L101-102), https://docs.rs/tonic/0.14.6/tonic/client/struct.Grpc.html (확인 2026-10-23)
- [S22] `tonic-health` / `tonic-reflection` 0.14.6 — https://docs.rs/tonic-health/0.14.6/tonic_health/ , https://docs.rs/tonic-reflection/0.14.6/tonic_reflection/ (확인 2026-10-23)
- [S23] tonic v0.14.0 릴리스 노트(prost 분리) — https://github.com/grpc/grpc-rust/releases/tag/v0.14.0 ; `tonic-prost-build` 0.14.6 https://docs.rs/tonic-prost-build/0.14.6/tonic_prost_build/struct.Builder.html (확인 2026-10-23)
- [S24] prost — https://github.com/tokio-rs/prost/releases/tag/v0.11.0 (protoc 필요), https://github.com/tokio-rs/prost/releases/tag/v0.14.4 , https://docs.rs/prost-build/0.14.4/prost_build/struct.Config.html (`bytes`) (확인 2026-10-23)
- [S25] protox 0.10.0 — https://crates.io/crates/protox (확인 2026-10-23)
- [S26] grpc-go v1.84.0 — https://github.com/grpc/grpc-go/releases/tag/v1.84.0 , https://proxy.golang.org/google.golang.org/grpc/@latest , https://github.com/grpc/grpc-go/blob/v1.84.0/server.go (L61-62), https://github.com/grpc/grpc-go/blob/v1.84.0/clientconn.go (L139-140), https://pkg.go.dev/google.golang.org/grpc@v1.84.0#MaxRecvMsgSize (확인 2026-10-23)
- [S27] gRPC Name Resolution — https://github.com/grpc/grpc/blob/master/doc/naming.md ; https://github.com/grpc/grpc-go/blob/v1.84.0/internal/resolver/unix/unix.go (확인 2026-10-23)
- [S28] grpc-go `unix_abstract` 예제 — https://github.com/grpc/grpc-go/blob/v1.84.0/examples/features/unix_abstract/server/main.go (확인 2026-10-23)
- [S29] grpc-go health / reflection — https://pkg.go.dev/google.golang.org/grpc/health@v1.84.0 , https://pkg.go.dev/google.golang.org/grpc/reflection@v1.84.0 (확인 2026-10-23)
- [S30] Go 코드 생성 도구 — https://github.com/protocolbuffers/protobuf-go/releases/tag/v1.36.12 , https://github.com/grpc/grpc-go/releases/tag/cmd/protoc-gen-go-grpc/v1.6.2 , https://github.com/bufbuild/buf/releases/tag/v1.75.0 , proxy.golang.org `@latest` (확인 2026-10-23)
- [S31] grpc-go 버퍼·스트림 — https://github.com/grpc/grpc-go/releases/tag/v1.66.0 , https://pkg.go.dev/google.golang.org/grpc/mem@v1.84.0 , https://github.com/grpc/grpc-go/blob/v1.84.0/stream.go (L1687-1704, `SendMsg`), https://pkg.go.dev/net#UnixListener.SetUnlinkOnClose , https://github.com/tokio-rs/tokio/blob/tokio-1.54.1/tokio/src/net/unix/listener.rs (확인 2026-10-23)
- [S32] protobuf-go `consumeBytes` — https://github.com/protocolbuffers/protobuf-go/blob/v1.36.12/internal/impl/codec_gen.go ; grpc-go `encoding/proto/proto.go` https://github.com/grpc/grpc-go/blob/v1.84.0/encoding/proto/proto.go (확인 2026-10-23)
- [S33] prost/tonic 수신 경로 — https://github.com/tokio-rs/prost/blob/v0.14.4/prost/src/encoding.rs , https://github.com/grpc/grpc-rust/blob/tonic-v0.14.6/tonic/src/codec/buffer.rs (확인 2026-10-23)
- [S34] Rust FFI 비용 — https://blog.rust-lang.org/2015/04/24/Rust-Once-Run-Everywhere/ , https://doc.rust-lang.org/rustc/linker-plugin-lto.html (확인 2026-10-23)
- [S35] Rust `unsafe extern` — https://doc.rust-lang.org/book/ch20-01-unsafe-rust.html , https://doc.rust-lang.org/edition-guide/rust-2024/unsafe-extern.html (확인 2026-10-23)
- [S36] Rust 1.71.0 (`C-unwind`) — https://blog.rust-lang.org/2023/07/13/Rust-1.71.0/ ; RFC 2945 https://rust-lang.github.io/rfcs/2945-c-unwind-abi.html (확인 2026-10-23)
- [S37] Rust 1.81.0 (abort on uncaught unwind) — https://blog.rust-lang.org/2024/09/05/Rust-1.81.0/ (확인 2026-10-23)
- [S38] Rust Reference, 함수 ABI와 unwinding — https://doc.rust-lang.org/reference/items/functions.html (확인 2026-10-23)
- [S39] tokio 1.54.1 — https://docs.rs/tokio/latest/tokio/index.html , https://docs.rs/tokio/latest/tokio/task/fn.spawn_blocking.html , https://docs.rs/tokio/latest/tokio/runtime/struct.Builder.html , https://doc.rust-lang.org/std/thread/fn.available_parallelism.html (확인 2026-10-23)
- [S40] Go 1.26 릴리스 노트 — https://go.dev/doc/go1.26 (확인 2026-10-23)
- [S41] Go CL 646198 — https://go-review.googlesource.com/c/go/+/646198 (확인 2026-10-23)
- [S42] golang/go 이슈 19574 — https://github.com/golang/go/issues/19574 (확인 2026-10-23)
- [S43] Go `runtime/cgocall.go`, `runtime/proc.go` — https://github.com/golang/go/blob/go1.27.3/src/runtime/cgocall.go (L11-27, L153-167), https://github.com/golang/go/blob/go1.27.3/src/runtime/proc.go (L6743-6766) (확인 2026-10-23)
- [S44] Go `runtime` 패키지 문서(GOMAXPROCS, 시스템 콜 스레드) — https://pkg.go.dev/runtime (확인 2026-10-23)
- [S45] Go `runtime/debug` — https://pkg.go.dev/runtime/debug#SetMaxThreads , https://pkg.go.dev/runtime/debug#SetMemoryLimit (확인 2026-10-23)
- [S46] cmd/cgo 포인터 전달 규칙 — https://pkg.go.dev/cmd/cgo#hdr-Passing_pointers (확인 2026-10-23)
- [S47] Go 1.21 릴리스 노트, `runtime.Pinner` — https://go.dev/doc/go1.21 , https://pkg.go.dev/runtime#Pinner (확인 2026-10-23)
- [S48] Go cgo 위키 — https://go.dev/wiki/cgo (확인 2026-10-23)
- [S49] A Guide to the Go Garbage Collector — https://go.dev/doc/gc-guide (확인 2026-10-23)
- [S50] Go 1.19 릴리스 노트(GOMEMLIMIT) — https://go.dev/doc/go1.19 (확인 2026-10-23)
- [S51] Go 1.25 릴리스 노트(container-aware GOMAXPROCS) — https://go.dev/doc/go1.25 (확인 2026-10-23)
- [S52] Go `sync.Pool` — https://pkg.go.dev/sync#Pool (확인 2026-10-23)
- [S53] Go `runtime/signal_unix.go` — https://github.com/golang/go/blob/go1.27.3/src/runtime/signal_unix.go (L703-711, L861-862) (확인 2026-10-23)
- [S54] Rust std `stack_overflow.rs` — https://github.com/rust-lang/rust/blob/1.99.1/library/std/src/sys/pal/unix/stack_overflow.rs (L76-142) (확인 2026-10-23)
- [S55] Go 릴리스 이력 — https://go.dev/doc/devel/release , https://go.dev/dl/?mode=json (확인 2026-10-23)
- [S56] Rust 1.99.1 — https://blog.rust-lang.org/2026/10/15/Rust-1.99.1/ , https://static.rust-lang.org/dist/channel-rust-stable.toml (확인 2026-10-23)
- [S57] Docker Hub 공식 이미지 태그 — https://hub.docker.com/_/rust , https://hub.docker.com/_/golang (API `https://hub.docker.com/v2/repositories/library/{rust,golang}/tags`) (확인 2026-10-23)
- [S58] distroless — https://github.com/GoogleContainerTools/distroless/blob/main/README.md (확인 2026-10-23)
- [S59] Go `runtime/malloc.go` — https://github.com/golang/go/blob/go1.27.3/src/runtime/malloc.go (L1051) (확인 2026-10-23)
- [S60] libwebp `decode.h`@v1.6.0 (`WebPDecodeRGBAInto`, `is_external_memory`) — https://chromium.googlesource.com/webm/libwebp/+/refs/tags/v1.6.0/src/webp/decode.h (확인 2026-10-23)
- [S61] Rust 플랫폼 지원 / musl 타깃 사양 — https://doc.rust-lang.org/nightly/rustc/platform-support.html , https://github.com/rust-lang/rust/blob/main/compiler/rustc_target/src/spec/targets/x86_64_unknown_linux_musl.rs , .../aarch64_unknown_linux_musl.rs (확인 2026-10-23)
- [S62] `cc` 크레이트 1.9.0 — https://docs.rs/cc/latest/cc/ , https://github.com/rust-lang/cc-rs/blob/main/src/lib.rs , https://github.com/rust-lang/cc-rs/blob/main/CHANGELOG.md (확인 2026-10-23)
- [S63] cross-rs — https://github.com/cross-rs/cross/releases , https://github.com/cross-rs/cross/blob/main/README.md (확인 2026-10-23)
- [S64] cargo-zigbuild v0.24.0, Zig — https://github.com/rust-cross/cargo-zigbuild/releases/tag/v0.24.0 , https://ziglang.org/download/index.json , https://andrewkelley.me/post/zig-cc-powerful-drop-in-replacement-gcc-clang.html (확인 2026-10-23)
- [S65] musl 할당자와 대체 할당자 — https://github.com/BurntSushi/ripgrep/blob/master/crates/core/main.rs , https://github.com/purpleprotocol/mimalloc_rust , https://github.com/tikv/jemallocator/blob/main/jemalloc-sys/README.md , https://musl.libc.org/releases.html (확인 2026-10-23)
- [S66] Docker multi-platform builds — https://docs.docker.com/build/building/multi-platform/ (확인 2026-10-23)
- [S67] glibc FAQ(정적 링크와 dlopen), Go `netgo`/`osusergo` — https://sourceware.org/glibc/wiki/FAQ , https://pkg.go.dev/net , https://pkg.go.dev/os/user , https://pkg.go.dev/cmd/link (확인 2026-10-23)
- [S68] Docker 공식 golang 이미지 문서 — https://github.com/docker-library/docs/blob/master/golang/README.md (확인 2026-10-23)
- [S69] cargo-fuzz — https://github.com/rust-fuzz/cargo-fuzz/releases , https://rust-fuzz.github.io/book/cargo-fuzz.html , https://rust-fuzz.github.io/book/cargo-fuzz/setup.html , https://github.com/rust-fuzz/cargo-fuzz/blob/main/src/project.rs (확인 2026-10-23)
- [S70] LLVM libFuzzer 문서 — https://llvm.org/docs/LibFuzzer.html (확인 2026-10-23)
- [S71] Go 퍼징 — https://go.dev/doc/security/fuzz/ , https://github.com/golang/go/blob/go1.27.3/src/internal/platform/supported.go , https://github.com/golang/go/blob/go1.27.3/src/cmd/go/internal/work/gc.go , https://github.com/golang/go/pull/77408 (확인 2026-10-23)
- [S72] OSS-Fuzz libwebp — https://github.com/google/oss-fuzz/tree/master/projects/libwebp (`project.yaml`, `build.sh`), https://chromium.googlesource.com/webm/libwebp/+/refs/heads/main/tests/fuzzer/ (확인 2026-10-23)
- [S73] OSS-Fuzz 언어 가이드와 기본 이미지 — https://google.github.io/oss-fuzz/getting-started/new-project-guide/ , https://google.github.io/oss-fuzz/getting-started/new-project-guide/rust-lang/ , https://google.github.io/oss-fuzz/getting-started/new-project-guide/go-lang/ , https://github.com/google/oss-fuzz/blob/master/infra/base-images/base-clang/Dockerfile (확인 2026-10-23)
- [S74] Docker seccomp — https://docs.docker.com/engine/security/seccomp/ , https://github.com/moby/profiles/blob/main/seccomp/default.json , https://github.com/moby/moby/pull/42681 (확인 2026-10-23)
- [S75] Go 런타임 시스템 콜과 선점 — https://go.googlesource.com/go/+/refs/heads/master/src/runtime/sys_linux_amd64.s , https://go.googlesource.com/go/+/refs/heads/master/src/internal/runtime/syscall/linux/ , https://go.dev/doc/go1.14#runtime , https://github.com/golang/go/blob/go1.27.3/src/runtime/signal_unix.go (`sigPreempt`), https://github.com/golang/go/blob/go1.27.3/src/syscall/syscall_linux.go (`Faccessat`), https://github.com/golang/go/blob/go1.27.3/src/os/pidfd_linux.go (확인 2026-10-23)
- [S76] mio/tokio와 Rust std — https://github.com/tokio-rs/mio/blob/master/src/sys/unix/mod.rs , https://github.com/tokio-rs/tokio/blob/tokio-1.54.1/tokio/src/lib.rs (`io-uring`, `tokio_unstable`), https://github.com/rust-lang/rust/blob/main/library/std/src/sys/fs/unix.rs (`statx` 폴백) (확인 2026-10-23)
- [S77] glibc NEWS(2.35 rseq) — https://github.com/bminor/glibc/blob/master/NEWS (확인 2026-10-23)
- [S78] seccomp 프로파일 도구 — https://github.com/kubernetes-sigs/security-profiles-operator , https://inspektor-gadget.io/docs/latest/gadgets/advise_seccomp/ , https://github.com/containers/oci-seccomp-bpf-hook , https://kubernetes.io/docs/tutorials/security/seccomp/ (확인 2026-10-23)
