# syntax=docker/dockerfile:1
# 버릴 코드(스파이크). ADR-0002 결정 후 삭제한다. 결과는 docs/research/0003에 기록.
#
# 기준 이미지 (2026-10-23 확인, 멀티 아키텍처 index digest로 고정)
#   golang:1.27.3-trixie                          sha256:fbb7ae40b62ff8afe31662df570d39b4f5052ac32b90dae8e60d7657a20a74f4
#   gcr.io/distroless/base-nossl-debian13:nonroot sha256:6d92083da644c5fceb58fb1dc50e88b3c1aa0d8b6099678223fbb801bd72962b
#   gcr.io/distroless/static-debian13:nonroot     sha256:d62067879de890a1def9da0e7766b7d20810a5e5555cef8506b752437d7a3e88
# libwebp: 공식 릴리스 tarball 1.6.0 (sha256 아래 ARG), 정적 라이브러리만 빌드
#
# 빌드/실행:
#   docker build -t spike-go-webp .                          # 기본: cgo + glibc 동적 + distroless/base-nossl
#   docker build --target static -t spike-go-webp:static .   # 선택: glibc 완전 정적 + distroless/static
#   docker run --rm --read-only --network none --cap-drop ALL \
#     --security-opt no-new-privileges -v "$PWD/../testdata:/data:ro" spike-go-webp

FROM golang:1.27.3-trixie@sha256:fbb7ae40b62ff8afe31662df570d39b4f5052ac32b90dae8e60d7657a20a74f4 AS libwebp
ARG LIBWEBP_VERSION=1.6.0
ARG LIBWEBP_SHA256=e4ab7009bf0629fd11982d4c2aa83964cf244cffba7347ecd39019a9e38c4564
WORKDIR /build
RUN curl -fsSLO "https://storage.googleapis.com/downloads.webmproject.org/releases/webp/libwebp-${LIBWEBP_VERSION}.tar.gz" \
 && echo "${LIBWEBP_SHA256}  libwebp-${LIBWEBP_VERSION}.tar.gz" | sha256sum -c - \
 && tar -xzf "libwebp-${LIBWEBP_VERSION}.tar.gz" \
 && cd "libwebp-${LIBWEBP_VERSION}" \
 && ./configure --prefix=/opt/libwebp --disable-shared --enable-static \
      --enable-libwebpmux --enable-libwebpdemux \
      --disable-png --disable-jpeg --disable-tiff --disable-gif --disable-wic --disable-sdl --disable-gl \
 && make -j"$(nproc)" \
 && make install

FROM libwebp AS build
WORKDIR /src
COPY go.mod ./
COPY main.go ./
ENV CGO_ENABLED=1 \
    CGO_CFLAGS="-I/opt/libwebp/include -O2" \
    CGO_LDFLAGS="-L/opt/libwebp/lib"
RUN go build -trimpath -ldflags="-s -w" -o /out/go-webp-decode . \
 && go build -trimpath -ldflags='-s -w -linkmode external -extldflags "-static"' -o /out/go-webp-decode-static .

FROM gcr.io/distroless/static-debian13:nonroot@sha256:d62067879de890a1def9da0e7766b7d20810a5e5555cef8506b752437d7a3e88 AS static
COPY --from=build /out/go-webp-decode-static /app/go-webp-decode
ENTRYPOINT ["/app/go-webp-decode"]
CMD ["/data/still.webp", "/data/anim.webp"]

FROM gcr.io/distroless/base-nossl-debian13:nonroot@sha256:6d92083da644c5fceb58fb1dc50e88b3c1aa0d8b6099678223fbb801bd72962b AS runtime
COPY --from=build /out/go-webp-decode /app/go-webp-decode
ENTRYPOINT ["/app/go-webp-decode"]
CMD ["/data/still.webp", "/data/anim.webp"]
