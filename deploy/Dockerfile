# syntax=docker/dockerfile:1
# webp-imageio-server 컨테이너 이미지: musl 정적 바이너리 + scratch (ADR-0012).
#
# 빌드(저장소 루트에서): docker build -f deploy/Dockerfile -t webp-imageio-server:dev .
# 벤치 참고용 AVX2 빌드(ADR-0015): ... --build-arg CARGO_FEATURES=simd-avx2 -t webp-imageio-server:dev-avx2 .
# 실행 예와 격리 옵션: deploy/README.md, deploy/compose.yaml
#
# 기준 이미지 (2026-10-23 확인, 멀티 아키텍처 index digest로 고정)
#   rust:1.99.1-alpine3.24  sha256:b1d5dd5a22a09404d3e798c0cf051b689077360374eb505fa5bb93c479693d87

FROM rust:1.99.1-alpine3.24@sha256:b1d5dd5a22a09404d3e798c0cf051b689077360374eb505fa5bb93c479693d87 AS build
# musl-dev: libwebp-sys가 libwebp C 소스를 컴파일한다. protoc: tonic-prost-build 코드 생성.
RUN apk add --no-cache musl-dev protoc
# server/rust-toolchain.toml 대신 이미지에 든 같은 버전(1.99.1)을 쓴다. 빌드 중 추가 컴포넌트 다운로드를 막는다.
ENV RUSTUP_TOOLCHAIN=1.99.1
WORKDIR /src
COPY proto ./proto
COPY server ./server
WORKDIR /src/server
# 빈 값이면 기본 빌드(libwebp SSE2). server/Cargo.toml [features] 참고.
ARG CARGO_FEATURES=""
RUN cargo build --release --locked ${CARGO_FEATURES:+--features "$CARGO_FEATURES"} \
 && strip target/release/webp-imageio-server \
 && mkdir -p /out/run/webp-imageio \
 && cp target/release/webp-imageio-server /out/

FROM scratch
COPY --from=build /out/webp-imageio-server /webp-imageio-server
# scratch에는 mkdir가 없으므로 소켓 디렉터리를 만들어 복사한다. 볼륨을 마운트하면 그 볼륨이 덮는다.
COPY --from=build --chown=65532:65532 /out/run/webp-imageio /run/webp-imageio
# 라이선스와 제3자 고지(ADR-0009): 프로젝트, libwebp(BSD-3-Clause + 특허 허여), Rust 의존성.
COPY LICENSE /licenses/LICENSE
COPY server/licenses/ /licenses/
USER 65532:65532
ENTRYPOINT ["/webp-imageio-server"]
