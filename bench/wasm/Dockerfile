# syntax=docker/dockerfile:1
# 벤치마크 전용: libwebp v1.6.0을 wasi-sdk로 Wasm(reactor 모듈, main 없음)으로 빌드한다.
# 제품 코드가 아니다. ADR-0001 선택지 5, ADR-0015 "Wasm(Chicory) 비교 구현".
#
# 고정한 입력 (2026-10-23 확인)
#   debian:trixie-20261020-slim  index digest sha256:62bd423aeee912639ca8ee681c2b762cb3e25137e0a92b20be455898a9f034ad
#   wasi-sdk-34 (34.0, LLVM 23.1.0) GitHub 릴리스 자산 sha256(아래 ARG)
#   libwebp 1.6.0 공식 tarball sha256(아래 ARG)
#
# 산출물만 꺼내기 (저장소 루트에서):
#   docker build --target artifact --output type=local,dest=bench/wasm/build bench/wasm
#   → bench/wasm/build/libwebp.wasm
#
# 빌드 결정 (근거: bench/wasm/README.md)
#   - SIMD 끔(-mno-simd128, WEBP_ENABLE_SIMD=OFF): Chicory 1.7.5는 Wasm SIMD를 인터프리터에서만 지원한다.
#   - 스레드 끔(WEBP_USE_THREAD=OFF, wasm32-wasip1 타깃): 단일 스레드 디코딩만 잰다.
#   - -mexec-model=reactor: main 없이 _initialize와 wp_* 함수만 내보낸다.
#   - 최대 선형 메모리 1 GiB(16384 페이지): 8192² lossless + 64 MiB 입력(ADR-0012 서버 기본 상한)을 담는다.

FROM debian:trixie-20261020-slim@sha256:62bd423aeee912639ca8ee681c2b762cb3e25137e0a92b20be455898a9f034ad AS build

ARG WASI_SDK_TAG=wasi-sdk-34
ARG WASI_SDK_VERSION=34.0
ARG WASI_SDK_SHA256_X86_64=b761e3a0721dbae9c09a0059e5fdb2bf917d1b4a8a7b430fb3b5aafb0984b2c4
# arm64 호스트용. 이 값으로 빌드해 보지는 않았다(미확인).
ARG WASI_SDK_SHA256_ARM64=f7e243dff54d60bcc576e94d6166b69f410f2500ae4a9ceef34315be10e77971
ARG LIBWEBP_VERSION=1.6.0
ARG LIBWEBP_SHA256=e4ab7009bf0629fd11982d4c2aa83964cf244cffba7347ecd39019a9e38c4564
# 최대 선형 메모리(바이트, 64 KiB 배수). Chicory 런타임 상한은 32767 페이지(약 2 GiB)다.
ARG WASM_MAX_MEMORY=1073741824
# Wasm 섀도 스택 크기(바이트). wasm-ld 기본값(64 KiB)보다 여유를 둔다.
ARG WASM_STACK_SIZE=1048576
ARG TARGETARCH

RUN apt-get update \
 && apt-get install -y --no-install-recommends ca-certificates curl cmake make \
 && rm -rf /var/lib/apt/lists/*

# 툴체인: 체크섬을 확인한 wasi-sdk 릴리스 바이너리만 실행한다.
WORKDIR /dl/wasi-sdk
RUN case "${TARGETARCH:-amd64}" in \
      amd64) arch=x86_64; sha="${WASI_SDK_SHA256_X86_64}" ;; \
      arm64) arch=arm64;  sha="${WASI_SDK_SHA256_ARM64}" ;; \
      *) echo "unsupported TARGETARCH: ${TARGETARCH}" >&2; exit 1 ;; \
    esac \
 && file="wasi-sdk-${WASI_SDK_VERSION}-${arch}-linux.tar.gz" \
 && curl -fsSLo "${file}" "https://github.com/WebAssembly/wasi-sdk/releases/download/${WASI_SDK_TAG}/${file}" \
 && echo "${sha}  ${file}" | sha256sum -c - \
 && mkdir -p /opt/wasi-sdk \
 && tar -xzf "${file}" -C /opt/wasi-sdk --strip-components=1 \
 && rm "${file}" \
 && cat /opt/wasi-sdk/VERSION

# libwebp 소스: 공식 릴리스 tarball을 체크섬으로 확인한다. 소스의 스크립트는 실행하지 않는다(CMake만 사용).
WORKDIR /dl/libwebp
RUN curl -fsSLO "https://storage.googleapis.com/downloads.webmproject.org/releases/webp/libwebp-${LIBWEBP_VERSION}.tar.gz" \
 && echo "${LIBWEBP_SHA256}  libwebp-${LIBWEBP_VERSION}.tar.gz" | sha256sum -c - \
 && mkdir -p /src/libwebp \
 && tar -xzf "libwebp-${LIBWEBP_VERSION}.tar.gz" -C /src/libwebp --strip-components=1 \
 && rm "libwebp-${LIBWEBP_VERSION}.tar.gz"

# 디코더 전용 정적 라이브러리(libwebpdecoder.a)만 만든다. 도구·mux·demux·이미지 라이브러리는 끈다.
RUN cmake -S /src/libwebp -B /build/libwebp \
      -DCMAKE_TOOLCHAIN_FILE=/opt/wasi-sdk/share/cmake/wasi-sdk-p1.cmake \
      -DWASI_SDK_PREFIX=/opt/wasi-sdk \
      -DCMAKE_BUILD_TYPE=Release \
      -DCMAKE_C_FLAGS="-mno-simd128" \
      -DBUILD_SHARED_LIBS=OFF \
      -DWEBP_ENABLE_SIMD=OFF \
      -DWEBP_USE_THREAD=OFF \
      -DWEBP_BUILD_ANIM_UTILS=OFF -DWEBP_BUILD_CWEBP=OFF -DWEBP_BUILD_DWEBP=OFF \
      -DWEBP_BUILD_GIF2WEBP=OFF -DWEBP_BUILD_IMG2WEBP=OFF -DWEBP_BUILD_VWEBP=OFF \
      -DWEBP_BUILD_WEBPINFO=OFF -DWEBP_BUILD_LIBWEBPMUX=OFF -DWEBP_BUILD_WEBPMUX=OFF \
      -DWEBP_BUILD_EXTRAS=OFF \
 && cmake --build /build/libwebp --target webpdecoder -j"$(nproc)"

# shim과 링크해 reactor 모듈을 만든다. 내보내기는 export_name 속성이 붙은 함수, _initialize, memory뿐이다.
COPY shim.c /src/shim/shim.c
RUN mkdir -p /out \
 && /opt/wasi-sdk/bin/clang --target=wasm32-wasip1 -O3 -DNDEBUG -mno-simd128 \
      -mexec-model=reactor \
      -I/src/libwebp/src \
      /src/shim/shim.c /build/libwebp/libwebpdecoder.a \
      -Wl,--max-memory="${WASM_MAX_MEMORY}" \
      -Wl,-z,stack-size="${WASM_STACK_SIZE}" \
      -Wl,--strip-debug \
      -o /out/libwebp.wasm \
 && /opt/wasi-sdk/bin/llvm-objdump --section-headers /out/libwebp.wasm \
 && ls -l /out/libwebp.wasm \
 && sha256sum /out/libwebp.wasm

FROM scratch AS artifact
COPY --from=build /out/libwebp.wasm /libwebp.wasm
