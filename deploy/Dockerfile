# syntax=docker/dockerfile:1
# webp-imageio-server 컨테이너 이미지: glibc 동적 바이너리 + distroless/cc (ADR-0021).
#
# 빌드(저장소 루트에서): docker build -f deploy/Dockerfile -t webp-imageio-server:dev .
# 벤치 참고용 AVX2 빌드(ADR-0015): ... --build-arg CARGO_FEATURES=simd-avx2 -t webp-imageio-server:dev-avx2 .
# 실행 예와 격리 옵션: deploy/README.md, deploy/compose.yaml
#
# 기준 이미지 (2026-11-02 확인, 멀티 아키텍처 index digest로 고정)
#   rust:1.99.1-slim-trixie                 sha256:b8e062b1c3a80183df83d9ba187cadd2b5ab84bc78d453dbfeb2fccd5f982247
#   gcr.io/distroless/cc-debian13:nonroot   sha256:08476c244888bfb425bae1ae4dd7c17f093e972aab8426883d97f3c6f280f7c1
# 두 이미지는 같은 Debian 13 glibc(libc6 2.41-12+deb13u4)를 쓴다. 빌드 쪽 glibc가 실행 쪽보다 새로우면 바이너리가
# 실행 쪽에 없는 심볼 버전을 요구할 수 있으므로, 둘을 함께 올린다(ADR-0021).
ARG RUNTIME_IMAGE=gcr.io/distroless/cc-debian13:nonroot@sha256:08476c244888bfb425bae1ae4dd7c17f093e972aab8426883d97f3c6f280f7c1

FROM ${RUNTIME_IMAGE} AS runtime

FROM rust:1.99.1-slim-trixie@sha256:b8e062b1c3a80183df83d9ba187cadd2b5ab84bc78d453dbfeb2fccd5f982247 AS build
# protobuf-compiler: tonic-prost-build 코드 생성. libwebp C 소스는 이미지에 든 gcc로 컴파일한다.
RUN apt-get update \
 && apt-get install -y --no-install-recommends protobuf-compiler \
 && rm -rf /var/lib/apt/lists/*
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
 && mkdir -p /out/run/webp-imageio /out/licenses \
 && cp target/release/webp-imageio-server /out/
# 베이스 이미지 패키지 목록(제3자 고지, ADR-0021): 실행 이미지의 dpkg 상태에서 이름·버전·소스 패키지를 뽑는다.
# 패키지별 라이선스 전문은 실행 이미지의 /usr/share/doc/<패키지>/copyright에 있다.
COPY --from=runtime /var/lib/dpkg/status.d /tmp/status.d
# 소스 버전은 Source 줄의 괄호 값(binNMU처럼 바이너리 버전과 다를 때)이고, 없으면 바이너리 버전과 같다.
RUN { echo "# 베이스 이미지 패키지 (ADR-0021). 라이선스: /usr/share/doc/<package>/copyright"; \
      echo "# 소스: https://snapshot.debian.org/package/<source>/<source_version>/"; \
      printf 'package\tversion\tsource\tsource_version\n'; \
      for f in /tmp/status.d/*; do \
        case "$f" in *.md5sums) continue ;; esac; \
        awk '/^Package:/ { p = $2 } /^Version:/ { v = $2 } \
             /^Source:/ { s = $2; if (NF >= 3) { sv = $3; gsub(/[()]/, "", sv) } } \
             END { printf "%s\t%s\t%s\t%s\n", p, v, (s == "" ? p : s), (sv == "" ? v : sv) }' "$f"; \
      done | sort; } >/out/licenses/BASE-IMAGE-PACKAGES.tsv

FROM runtime
COPY --from=build /out/webp-imageio-server /webp-imageio-server
# distroless에는 셸과 mkdir가 없으므로 소켓 디렉터리를 만들어 복사한다. 볼륨을 마운트하면 그 볼륨이 덮는다.
COPY --from=build --chown=65532:65532 /out/run/webp-imageio /run/webp-imageio
# 라이선스와 제3자 고지(ADR-0009, ADR-0021): 프로젝트, libwebp(BSD-3-Clause + 특허 허여), Rust 의존성,
# 베이스 이미지 패키지 목록(glibc LGPL-2.1+ 등. 전문은 /usr/share/doc).
COPY LICENSE /licenses/LICENSE
COPY server/licenses/ /licenses/
COPY --from=build /out/licenses/BASE-IMAGE-PACKAGES.tsv /licenses/BASE-IMAGE-PACKAGES.tsv
# ADR-0021: glibc arena를 기본 동시 처리 수(2)만큼으로 묶는다. 서버의 유휴 trim(malloc_trim)은 스레드별 arena의
# 꼭대기를 줄이지 못해, 기본값(arena가 스레드마다 늘어남)이면 부하 뒤 수십 MiB가 남는다(research/0015).
# 동시 처리(WEBP_SERVER_MAX_CONCURRENCY)를 늘리면 실행할 때 -e MALLOC_ARENA_MAX=<값>으로 함께 올린다.
ENV MALLOC_ARENA_MAX=2
USER 65532:65532
ENTRYPOINT ["/webp-imageio-server"]
