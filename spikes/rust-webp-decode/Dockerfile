# syntax=docker/dockerfile:1
# 버릴 코드(스파이크). ADR-0002 결정 후 삭제한다. 결과는 docs/research/0003에 기록.
#
# 기준 이미지 (2026-10-23 확인, 멀티 아키텍처 index digest로 고정)
#   rust:1.99.1-slim-trixie      sha256:aab42391a986424e22d6a3ec14f37bc6dd3dacff4d457ca7cccd781e02297cc6
#   rust:1.99.1-alpine3.24       sha256:b1d5dd5a22a09404d3e798c0cf051b689077360374eb505fa5bb93c479693d87
#   gcr.io/distroless/cc-debian13:nonroot  sha256:83b9b50586253821cf63ffcec87d3d94265a093ad30da232039af962dd8f4edf
#
# 빌드/실행:
#   docker build -t spike-rust-webp .                        # 기본: glibc + distroless/cc
#   docker build --target musl -t spike-rust-webp:musl .     # 선택: musl 정적 + scratch
#   docker run --rm --read-only --network none --cap-drop ALL \
#     --security-opt no-new-privileges -v "$PWD/../testdata:/data:ro" spike-rust-webp

# ---- musl 정적 빌드 (선택 타깃) ----
FROM rust:1.99.1-alpine3.24@sha256:b1d5dd5a22a09404d3e798c0cf051b689077360374eb505fa5bb93c479693d87 AS build-musl
RUN apk add --no-cache musl-dev
WORKDIR /src
COPY Cargo.toml Cargo.lock ./
COPY src ./src
RUN cargo build --release --locked && strip target/release/rust-webp-decode

FROM scratch AS musl
COPY --from=build-musl /src/target/release/rust-webp-decode /rust-webp-decode
USER 65532:65532
ENTRYPOINT ["/rust-webp-decode"]
CMD ["/data/still.webp", "/data/anim.webp"]

# ---- glibc 빌드 (기본 타깃) ----
FROM rust:1.99.1-slim-trixie@sha256:aab42391a986424e22d6a3ec14f37bc6dd3dacff4d457ca7cccd781e02297cc6 AS build
WORKDIR /src
COPY Cargo.toml Cargo.lock ./
COPY src ./src
RUN cargo build --release --locked && strip target/release/rust-webp-decode

FROM gcr.io/distroless/cc-debian13:nonroot@sha256:83b9b50586253821cf63ffcec87d3d94265a093ad30da232039af962dd8f4edf AS runtime
COPY --from=build /src/target/release/rust-webp-decode /app/rust-webp-decode
ENTRYPOINT ["/app/rust-webp-decode"]
CMD ["/data/still.webp", "/data/anim.webp"]
