# server

libwebp를 직접 링크한 gRPC 서버. 같은 호스트/파드의 사이드카로 실행하고 Unix Domain Socket을 기본 전송으로 쓴다.

- 구현 언어: Rust — tonic + prost + `libwebp-sys`([ADR-0002](../docs/adr/0002-server-language.md)). libwebp는 v1.6.0 태그를 쓴다([ADR-0008](../docs/adr/0008-libwebp-version-pinning.md)).
- 요청마다 `cwebp`/`dwebp` CLI를 실행하는 방식은 쓰지 않는다.
- 서버 상한(픽셀 수, 입력 크기 등)은 설정값으로 받는다([grpc-api.md §3](../docs/design/grpc-api.md)).
- 코드는 Phase 1에서 추가한다([roadmap](../docs/roadmap.md)).

## 개발 환경 준비 (Ubuntu, apt)

```sh
# rustup(툴체인 관리자), C 컴파일러(libwebp-sys가 libwebp C 소스를 빌드), protoc(prost/tonic 코드 생성)
sudo apt install rustup build-essential pkg-config protobuf-compiler
rustup default stable

# 선택: musl 정적 바이너리(scratch 이미지용)
sudo apt install musl-tools
rustup target add x86_64-unknown-linux-musl
```

- apt의 `rustup` 패키지는 `rustc`/`cargo` 패키지와 충돌한다. rustup만 설치한다.
- 툴체인 버전은 Phase 1에서 `server/rust-toolchain.toml`로 고정한다. 그 파일이 있으면 rustup이 해당 버전을 자동으로 받는다.
- 로컬 툴체인 없이 Docker만으로도 빌드할 수 있다(예: `spikes/rust-webp-decode/Dockerfile`).
- Windows는 `rustup-init.exe`와 Visual Studio Build Tools(C++ 빌드 도구)가 필요하다(이 저장소에서 검증하지 않음).

## 실행 구성

이름, 설정 방식, 상한 기본값, 동시 처리, 컨테이너 제한, 소켓은 [ADR-0012](../docs/adr/0012-server-runtime-config.md)를 따른다. 설정 키 표는 [grpc-api.md §3.1](../docs/design/grpc-api.md).

## 구현 기본값 (Phase 1)

바꾸려면 이 목록을 고치고 이유를 커밋 메시지에 적는다. 설계를 바꾸는 수준이면 ADR을 쓴다.

- 툴체인: Phase 1 시작 시점의 최신 stable을 `server/rust-toolchain.toml`로 고정하고 `Cargo.lock`을 커밋한다.
- 코드 생성: `build.rs` + `tonic-prost-build` + protoc.
- 구조: 단일 크레이트. 모듈은 설정(config), libwebp FFI(webp — `unsafe`는 여기에만), gRPC 서비스(service).
- 로깅: `tracing`, stderr 출력, 수준은 `RUST_LOG`.
- 린트: rustfmt, `cargo clippy -- -D warnings`, `missing_docs`, `clippy::undocumented_unsafe_blocks`(모든 `unsafe` 블록에 `// SAFETY:` 주석).
- 의존성 라이선스: `cargo-deny`로 허용 목록을 검사하고, 컨테이너 이미지에 제3자 고지 파일을 넣는다([ADR-0009](../docs/adr/0009-license.md)).
