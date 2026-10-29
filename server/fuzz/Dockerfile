# 서버 퍼징 환경: Rust nightly(cargo-fuzz, -Zsanitizer=address) + rustc와 같은 LLVM 버전의 clang.
# clang은 libwebp C 코드도 ASan과 libFuzzer 커버리지로 계측하려고 쓴다(rustc의 ASan 런타임과 LLVM 버전을 맞춘다).
# 빌드: server/fuzz/run.sh가 한다. 기준 이미지(2026-10-29 확인): rustlang/rust:nightly-trixie,
# 툴체인 nightly-2026-10-28(rustc 1.101.0-nightly, LLVM 23.1.4). run.sh가 이 이름으로 고정한다.
FROM rustlang/rust:nightly-trixie@sha256:fcf50648218ecb82fbe8e9e70bf4df8c129150e93c2f6ed937bf8b04146ac665
ARG LLVM_MAJOR=23
# protobuf-compiler: 서버 크레이트의 build.rs(tonic-prost-build).
RUN apt-get update \
 && apt-get install -y --no-install-recommends ca-certificates curl gnupg protobuf-compiler \
 && curl -fsSL https://apt.llvm.org/llvm-snapshot.gpg.key | gpg --dearmor -o /usr/share/keyrings/apt-llvm.gpg \
 && echo "deb [signed-by=/usr/share/keyrings/apt-llvm.gpg] https://apt.llvm.org/trixie/ llvm-toolchain-trixie-${LLVM_MAJOR} main" \
      > /etc/apt/sources.list.d/llvm.list \
 && apt-get update \
 && apt-get install -y --no-install-recommends clang-${LLVM_MAJOR} \
 && rm -rf /var/lib/apt/lists/* \
 && rustc -vV | grep LLVM && clang-${LLVM_MAJOR} --version | head -1
RUN cargo install cargo-fuzz --version 0.13.2 --locked
