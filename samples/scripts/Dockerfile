# syntax=docker/dockerfile:1
# 테스트 샘플 생성 도구 이미지 (ADR-0006).
#
# libwebp v1.6.0을 공식 tarball에서 빌드하고(서버와 같은 버전 — ADR-0008), 예제 도구
# (cwebp, dwebp, img2webp, gif2webp, webpmux, webpinfo, anim_dump, anim_diff)와 기대값용
# 보조 도구 webp_probe(webp_probe.c), python3를 담는다. 호스트의 libwebp(예: 1.5.0)는 쓰지 않는다.
#
# 기준 이미지 (2026-10-23 확인, 멀티 아키텍처 index digest로 고정)
#   debian:trixie-20261020-slim  sha256:62bd423aeee912639ca8ee681c2b762cb3e25137e0a92b20be455898a9f034ad
# libwebp 1.6.0 tarball sha256 (ADR-0008)
#   e4ab7009bf0629fd11982d4c2aa83964cf244cffba7347ecd39019a9e38c4564
# apt 패키지 버전은 고정하지 않는다(trixie 저장소 기준). 커밋되는 출력은 libwebp만 결정한다.
#
# 빌드/실행 (저장소 루트에서): samples/README.md "다시 만들기" 참고
#   docker build -t webp-samples-tools:libwebp-1.6.0 samples/scripts
#   docker run --rm --network none -u "$(id -u):$(id -g)" -v "$PWD:/repo" -w /repo \
#     webp-samples-tools:libwebp-1.6.0 samples/scripts/build.sh

ARG BASE_IMAGE=debian:trixie-20261020-slim@sha256:62bd423aeee912639ca8ee681c2b762cb3e25137e0a92b20be455898a9f034ad

# ---- libwebp 빌드 ----
FROM ${BASE_IMAGE} AS build
ARG LIBWEBP_VERSION=1.6.0
ARG LIBWEBP_SHA256=e4ab7009bf0629fd11982d4c2aa83964cf244cffba7347ecd39019a9e38c4564
# libtiff는 넣지 않는다. Debian libtiff6가 시스템 libwebp(다른 버전)를 끌어오기 때문이다.
RUN apt-get update \
 && apt-get install -y --no-install-recommends ca-certificates curl cmake make gcc libc6-dev \
      libpng-dev libgif-dev \
 && rm -rf /var/lib/apt/lists/*
WORKDIR /build
# 내려받은 tarball은 sha256을 확인한 뒤에만 푼다.
RUN curl -fsSLo libwebp.tar.gz \
      "https://storage.googleapis.com/downloads.webmproject.org/releases/webp/libwebp-${LIBWEBP_VERSION}.tar.gz" \
 && echo "${LIBWEBP_SHA256}  libwebp.tar.gz" | sha256sum -c - \
 && tar -xzf libwebp.tar.gz
# 정적 라이브러리로 빌드해 도구가 시스템 libwebp를 동적으로 읽을 여지를 없앤다.
RUN cmake -S "libwebp-${LIBWEBP_VERSION}" -B out \
      -DCMAKE_BUILD_TYPE=Release \
      -DCMAKE_INSTALL_PREFIX=/opt/libwebp \
      -DBUILD_SHARED_LIBS=OFF \
      -DWEBP_BUILD_ANIM_UTILS=ON -DWEBP_BUILD_CWEBP=ON -DWEBP_BUILD_DWEBP=ON \
      -DWEBP_BUILD_GIF2WEBP=ON -DWEBP_BUILD_IMG2WEBP=ON -DWEBP_BUILD_WEBPINFO=ON \
      -DWEBP_BUILD_WEBPMUX=ON -DWEBP_BUILD_VWEBP=OFF -DWEBP_BUILD_EXTRAS=OFF \
 && cmake --build out -j"$(nproc)" \
 && cmake --install out \
 && cp out/anim_dump out/anim_diff /opt/libwebp/bin/
COPY webp_probe.c /build/webp_probe.c
RUN gcc -std=c11 -O2 -Wall -Wextra -Werror -I/opt/libwebp/include /build/webp_probe.c \
      -o /opt/libwebp/bin/webp_probe \
      /opt/libwebp/lib/libwebpdemux.a /opt/libwebp/lib/libwebp.a /opt/libwebp/lib/libsharpyuv.a \
      -lm -pthread

# ---- 실행 이미지 ----
FROM ${BASE_IMAGE} AS tools
RUN apt-get update \
 && apt-get install -y --no-install-recommends ca-certificates python3 libpng16-16t64 libgif7 \
 && rm -rf /var/lib/apt/lists/*
COPY --from=build /opt/libwebp/bin/ /usr/local/bin/
# 버전과 링크를 확인한다: 모든 도구가 1.6.0이고, 시스템 libwebp 패키지가 없어야 한다.
RUN set -e; \
    test "$(cwebp -version | head -n1)" = "1.6.0"; \
    test "$(dwebp -version | head -n1)" = "1.6.0"; \
    for t in cwebp dwebp img2webp gif2webp webpmux webpinfo anim_dump anim_diff webp_probe; do \
      command -v "$t" >/dev/null; \
      if ldd "$(command -v "$t")" | grep -qi webp; then echo "unexpected shared libwebp: $t" >&2; exit 1; fi; \
    done; \
    if dpkg -l | grep -qi webp; then echo "system libwebp package present" >&2; exit 1; fi
ENV LIBWEBP_VERSION=1.6.0 \
    LIBWEBP_SHA256=e4ab7009bf0629fd11982d4c2aa83964cf244cffba7347ecd39019a9e38c4564
WORKDIR /repo
