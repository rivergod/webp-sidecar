# libwebp 버전별 디코딩 시간 측정 이미지(사진 alpha 격차 조사, docs/research/0013-alpha-decode-gap.md).
#
# libwebp 1.3.2(darkxanter JNI 플러그인 내장 버전)와 1.6.0(서버 버전)을 공식 tarball에서 같은 조건(CMake Release,
# 정적, 기본 SIMD 런타임 선택)으로 빌드하고, 같은 측정 프로그램(alpha_decode.c)을 각각에 링크한다.
#
# 기준 이미지: samples/scripts/Dockerfile과 같다(debian:trixie-20261020-slim, digest 고정).
# tarball sha256(2026-10-30 확인). 둘 다 "WebP release signing key" (6B0E6B70976DE303EDF2F601F9C3D6BDB8232B5D)의 .asc
# 서명이 유효했고, 1.6.0 값은 ADR-0008·samples/scripts/Dockerfile의 고정값과 같다.
#   libwebp-1.3.2.tar.gz  2a499607df669e40258e53d0ade8035ba4ec0175244869d1025d460562aa09b4
#   libwebp-1.6.0.tar.gz  e4ab7009bf0629fd11982d4c2aa83964cf244cffba7347ecd39019a9e38c4564
#
# 빌드·실행: bench/libwebp-alpha/run.sh
ARG BASE_IMAGE=debian:trixie-20261020-slim@sha256:62bd423aeee912639ca8ee681c2b762cb3e25137e0a92b20be455898a9f034ad

FROM ${BASE_IMAGE} AS build
RUN apt-get update \
 && apt-get install -y --no-install-recommends ca-certificates curl cmake make gcc libc6-dev \
 && rm -rf /var/lib/apt/lists/*
WORKDIR /build
COPY alpha_decode.c /build/alpha_decode.c
# 내려받은 tarball은 sha256을 확인한 뒤에만 푼다.
RUN set -e; \
    for spec in 1.3.2:2a499607df669e40258e53d0ade8035ba4ec0175244869d1025d460562aa09b4 \
                1.6.0:e4ab7009bf0629fd11982d4c2aa83964cf244cffba7347ecd39019a9e38c4564; do \
      v=${spec%%:*}; sum=${spec#*:}; \
      curl -fsSLo libwebp-$v.tar.gz "https://storage.googleapis.com/downloads.webmproject.org/releases/webp/libwebp-$v.tar.gz"; \
      echo "$sum  libwebp-$v.tar.gz" | sha256sum -c -; \
      tar -xzf libwebp-$v.tar.gz; \
      cmake -S libwebp-$v -B out-$v -DCMAKE_BUILD_TYPE=Release -DCMAKE_INSTALL_PREFIX=/opt/libwebp-$v \
        -DBUILD_SHARED_LIBS=OFF -DWEBP_BUILD_ANIM_UTILS=OFF -DWEBP_BUILD_CWEBP=OFF -DWEBP_BUILD_DWEBP=OFF \
        -DWEBP_BUILD_GIF2WEBP=OFF -DWEBP_BUILD_IMG2WEBP=OFF -DWEBP_BUILD_WEBPINFO=OFF -DWEBP_BUILD_WEBPMUX=OFF \
        -DWEBP_BUILD_VWEBP=OFF -DWEBP_BUILD_EXTRAS=OFF; \
      cmake --build out-$v -j"$(nproc)"; \
      cmake --install out-$v; \
      gcc -std=c11 -O2 -Wall -Wextra -Werror -I/opt/libwebp-$v/include alpha_decode.c -o /opt/alpha_decode-$v \
        /opt/libwebp-$v/lib/libwebp.a /opt/libwebp-$v/lib/libsharpyuv.a -lm -pthread; \
    done

FROM ${BASE_IMAGE}
COPY --from=build /opt/alpha_decode-1.3.2 /opt/alpha_decode-1.6.0 /usr/local/bin/
USER 65532:65532
