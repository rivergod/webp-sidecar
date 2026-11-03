# 0006. 라이선스 후보와 의존성 호환성

- 상태: 초안
- 작성일/확인일: 2026-10-23, 보강 2026-11-02(glibc 동적 이미지, ADR-0021)
- 관련 ADR: ADR-0009, ADR-0010, ADR-0021

> 법률 자문이 아니다. 공개 문서와 패키지 메타데이터로 확인한 사실과 일반적인 해석을 정리했다.

## 요약

- 서버가 링크할 libwebp는 BSD-3-Clause이고, Google의 별도 특허 허여(PATENTS)가 붙어 있다 [S1][S2]. 서버 Rust 의존성의 직접 의존 크레이트는 MIT, Apache-2.0, `MIT OR Apache-2.0`이다 [S3].
- 클라이언트 런타임 의존성(grpc-java, Netty, Guava)은 Apache-2.0이고 protobuf-java는 BSD-3-Clause다 [S4].
- 테스트·벤치 전용 의존성 가운데 JMH만 copyleft다(GPL-2.0 + Classpath Exception) [S4]. 벤치 산출물을 배포하지 않으면 문제가 되지 않는다.
- Apache-2.0 의존성이 있으므로 **GPL-2.0-only는 쓸 수 없다**. FSF는 Apache-2.0을 GPLv2와 호환되지 않는다고 본다 [S5].
- 허용적 라이선스(Apache-2.0, MIT, BSD-3-Clause, `MIT OR Apache-2.0`)는 모두 의존성과 충돌하지 않는다. 주 사용처가 Spring 애플리케이션이라는 점을 고려하면 copyleft 계열은 사용자 쪽 부담이 크다.

## 의존성 라이선스 (확인 2026-10-23)

### 서버 (배포물: 컨테이너 이미지 안의 정적 바이너리)

| 구성 요소 | 버전 | 라이선스 | 출처 |
|---|---|---|---|
| libwebp | v1.6.0 | BSD-3-Clause (`COPYING`, "Copyright (c) 2010, Google Inc.") + 특허 허여(`PATENTS`) | [S1][S2] |
| libwebp-sys | 0.14.4 | MIT | [S3] |
| tonic, tonic-health, tonic-prost, tonic-prost-build | 0.14.6 | MIT | [S3] |
| prost, prost-types | 0.14.4 | Apache-2.0 | [S3] |
| tokio / tokio-stream | 1.54.1 / 0.1.19 | MIT | [S3] |
| hyper / h2 / tower / bytes | 1.12.0 / 0.4.20 / 0.5.3 / 1.12.1 | MIT | [S3] |
| http | 1.5.0 | MIT OR Apache-2.0 | [S3] |
| musl libc (정적 링크 시) | - | MIT | [S6] |
| glibc (동적 링크 시) | - | LGPL-2.1 (`COPYING.LIB` → `COPYING.LESSERv2`) | [S7] |

- 위 표는 **직접 의존 크레이트만** 다룬다. 전이 의존성 전체는 서버 `Cargo.toml`이 생기는 Phase 1에서 `cargo-deny`(허용 라이선스 목록 검사)와 `cargo-about`(고지 파일 생성) 같은 도구로 확인한다(도구 선택은 미정).
- 정적 바이너리는 링크한 코드를 함께 재배포하는 것이다. 따라서 BSD-3-Clause의 "바이너리 재배포 시 문서나 자료에 고지를 재현" 조건 [S1]과 MIT/Apache-2.0의 고지 조건이 이미지에 적용된다.
- glibc를 **정적** 링크하면 LGPL의 재링크 허용 의무가 생긴다(일반적 해석). musl 정적 링크나 glibc 동적 링크는 이 문제가 없다.

### 클라이언트 (배포물: 플러그인 jar, 의존성은 shading하지 않음 — ADR-0005)

| 구성 요소 | 버전 | 라이선스 | 출처 |
|---|---|---|---|
| grpc-netty, grpc-protobuf, grpc-stub, grpc-api | 1.85.1 | Apache 2.0 | [S4] |
| protobuf-java | 4.36.2 (부모 POM) | BSD-3-Clause | [S4] |
| Netty | 4.2.17.Final (부모 POM) | Apache License 2.0 | [S4] |
| Guava | 33.7.2-jre (부모 POM) | Apache License 2.0 | [S4] |

shading하지 않으므로 이 jar는 의존성 코드를 담지 않는다. 사용자의 빌드 도구가 각 의존성을 따로 받는다.

### 테스트·벤치 전용 (배포하지 않음)

| 구성 요소 | 버전 | 라이선스 | 출처 |
|---|---|---|---|
| Testcontainers | 2.0.5 | MIT | [S4] |
| JMH | 1.37 | GPL-2.0 + Classpath Exception | [S4] |
| TwelveMonkeys imageio-webp | 3.15.3 | BSD ("The BSD License") | [S4] |
| darkxanter webp-imageio | 0.3.3 | Apache-2.0 | [S4] |
| Chicory runtime | 1.7.5 | Apache-2.0 | [S4] |

### 저장소 안의 제3자 내용

- `docs/conventions/working-principles.md`: andrej-karpathy-skills 발췌, MIT. 저작권 줄은 원 저장소에 없어 추정으로 적었다([research/0002](0002-agent-glue.md)).
- `samples/third_party/`(아직 없음): PngSuite 허가 문구, CC0 ICC 프로파일 등 파일별 라이선스([research/0005](0005-test-samples.md)).

## 라이선스 후보

| 라이선스 | 성격 | 의존성과 호환 | 사용자(Spring 앱) 쪽 영향 | 판단 |
|---|---|---|---|---|
| **Apache-2.0** | 허용적, 명시적 특허 허여, 기여 조항 | 호환 | 고지 유지만 | 가능 |
| **MIT OR Apache-2.0** | 이중 라이선스(Rust 생태계 관례) | 호환 | 고지 유지만 | 가능 |
| **MIT** | 허용적, 짧음, 특허 조항 없음 | 호환 | 고지 유지만 | 가능 |
| **BSD-3-Clause** | 허용적, 이름 사용 제한 조항 | 호환(libwebp, protobuf와 같은 라이선스) | 고지 유지만 | 가능 |
| 0BSD / Unlicense | 고지 의무도 없는 퍼블릭 도메인 성격 | 호환 | 없음 | 가능하나 특허·면책 측면이 약하다 |
| MPL-2.0 | 파일 단위 copyleft | 호환(추정) | 수정한 MPL 파일의 소스 공개 | 가능하나 마찰이 있다 |
| LGPL-2.1 / LGPL-3.0 | 라이브러리 단위 copyleft | 호환 여부가 조합마다 다르다(추정) | Java jar에서 "재링크 허용" 의무의 해석이 모호하다 | 비추천 |
| EPL-2.0 | 약한 copyleft | 호환(추정) | 수정 파일 공개 | 가능하나 드물다 |
| GPL-3.0 | 강한 copyleft | Apache-2.0 코드를 포함할 수는 있다 [S5] | 플러그인을 쓰는 애플리케이션 배포물 전체에 GPL 의무가 생긴다 | 사실상 부적합 |
| GPL-2.0-only | 강한 copyleft | **비호환**: Apache-2.0 의존성(grpc-java, Netty, prost) [S5] | - | 불가 |
| AGPL-3.0 | 네트워크 copyleft | GPL-3.0과 같음 | 사이드카와 UDS로 상호작용하는 것이 "원격 상호작용"에 해당하는지 해석이 필요하다 | 부적합 |

## 어떤 라이선스를 고르든 필요한 일

1. **저작권자를 정한다.** 개인인지 조직인지 정해야 한다. 업무로 만든 결과물이면 고용 계약상 권리자가 따로 있을 수 있다(미확인, 사람이 판단).
2. **서버 이미지에 제3자 고지를 넣는다.** libwebp `COPYING`, 함께 넣으면 좋은 `PATENTS`, Rust 의존성 라이선스 전문을 담는다. Phase 1에서 고지 파일 생성과 허용 목록 검사를 빌드에 넣는다.
3. **glibc를 정적 링크하지 않는다**(musl 정적 또는 glibc 동적).
4. **벤치 모듈 산출물(JMH 포함)을 배포하지 않는다.**
5. 프로젝트 파일 머리에 `SPDX-License-Identifier`를 넣을지 정한다(선택).

## Maven 좌표 관련 사실

- Maven Central은 도메인 기반 namespace(`net.rivergod`)를 **정확히 그 도메인(`rivergod.net`)의 DNS TXT 레코드**로 확인한다 [S8].
- 도메인이 없으면 GitHub 가입 계정 기준의 `io.github.<사용자명>` namespace를 받을 수 있다. 조직 이름은 지원하지 않는다 [S8].
- Maven Central에 배포하지 않으면(로컬 저장소, 사내 저장소, GitHub Packages 등) 이 확인은 필요 없다.

## glibc 동적 이미지의 배포 의무 (확인 2026-11-02, ADR-0021)

서버 이미지를 musl 정적 + `scratch`에서 glibc 동적 + `gcr.io/distroless/cc-debian13:nonroot`로 바꾸는 경우를 다룬다. 프로젝트 라이선스(Apache-2.0)는 바뀌지 않는다. 위 "glibc를 정적 링크하지 않는다" 조건도 그대로 지킨다(동적 링크). 바뀌는 것은 **이미지가 Debian 패키지 바이너리를 함께 배포한다**는 점이다.

### 이미지에 들어가는 베이스 패키지

`gcr.io/distroless/cc-debian13:nonroot`(index digest `sha256:08476c244888bfb425bae1ae4dd7c17f093e972aab8426883d97f3c6f280f7c1`)의 dpkg 상태(`/var/lib/dpkg/status.d/`)와 패키지별 `/usr/share/doc/<패키지>/copyright`를 이미지에서 꺼내 확인했다 [S9].

| 패키지 | 버전 | 소스 패키지 | 주 라이선스(copyright 파일) | 서버가 쓰는가 |
|---|---|---|---|---|
| libc6 | 2.41-12+deb13u4 | glibc | LGPL-2.1+ (일부 파일 BSD 계열 등) | 쓴다(`libc.so.6`, `libm.so.6`, `ld-linux-x86-64.so.2`) |
| libgcc-s1 | 14.2.0-19 | gcc-14 | GPL-3+ + GCC Runtime Library Exception 3.1 | 쓴다(`libgcc_s.so.1`, Rust 언와인딩) |
| gcc-14-base | 14.2.0-19 | gcc-14 | (libgcc-s1과 같은 copyright) | 문서만 |
| libstdc++6, libgomp1 | 14.2.0-19 | gcc-14 | GPL-3+ + GCC Runtime Library Exception 3.1 | 쓰지 않는다 |
| libssl3t64 | 3.5.7-1~deb13u3 | openssl | Apache-2.0 | 쓰지 않는다 |
| libzstd1 | 1.5.7+dfsg-1 | libzstd | BSD-3-clause 또는 GPL-2 | 쓰지 않는다 |
| zlib1g | 1:1.3.dfsg+really1.3.1-1+b1 | zlib(1:1.3.dfsg+really1.3.1-1) | Zlib | 쓰지 않는다 |
| base-files | 13.8+deb13u7 | base-files | GPL-2+ | 데이터 파일(`/etc/os-release` 등, `/usr/share/common-licenses`) |
| netbase | 6.5 | netbase | GPL-2 | 데이터 파일 |
| ca-certificates | 20250419+deb13u1 | ca-certificates | GPL-2+(스크립트), MPL-2.0(인증서 데이터) | 쓰지 않는다 |
| tzdata, tzdata-legacy | 2026f-0+deb13u1 | tzdata | public-domain | 쓰지 않는다 |
| media-types | 13.0.0 | media-types | ad-hoc | 쓰지 않는다 |

- 서버 바이너리가 동적 링크하는 것은 `libgcc_s.so.1`, `libm.so.6`, `libc.so.6`, `ld-linux-x86-64.so.2` 넷이다(`readelf -d`, 요구 심볼 버전은 `GLIBC_2.34`까지).
- 라이선스 전문은 이미지 안에 있다. copyright 파일이 가리키는 `/usr/share/common-licenses/`(LGPL-2.1, GPL-2, GPL-3 등)를 base-files가 담는다.
- `gcr.io/distroless/base-nossl-debian13`에는 libc6만 있고 libgcc-s1이 없다. 서버는 `libgcc_s.so.1`이 필요하다. distroless의 Rust 예제도 `cc-debian13`을 쓴다 [S10].

### 배포할 때의 의무 (일반적 해석, 법률 판단은 첫 배포 전에 받는다)

| 의무 | 대상 | 지금 상태 |
|---|---|---|
| 라이선스 전문과 저작권 고지를 함께 준다 | 모든 패키지 | 충족. 이미지 안 `/usr/share/doc/*/copyright`와 `/usr/share/common-licenses/` |
| 대응 소스를 제공한다(LGPL-2.1 §4·§6, GPL-2 §3, GPL-3 §6) | libc6(glibc), libgcc-s1·libstdc++6·libgomp1(gcc-14), base-files, netbase, ca-certificates | **미정.** 정확한 소스 패키지와 버전을 이미지의 `/licenses/BASE-IMAGE-PACKAGES.tsv`에 남긴다. 소스는 `https://snapshot.debian.org/package/<소스>/<소스 버전>/`(URL 인코딩: `+` → `%2B`, `:` → `%3A`)에서 받을 수 있다(glibc 2.41-12+deb13u4, zlib 1:1.3.dfsg+really1.3.1-1 응답 200 확인) [S11]. 제3자 서버를 가리키는 것으로 충분한지, 서면 제공 약속이나 직접 보관이 필요한지는 해석이 갈린다(미확인) |
| 사용자가 LGPL 라이브러리를 바꿔 끼울 수 있게 한다(LGPL-2.1 §6) | glibc | 충족한다고 본다(추정). 동적 링크라 이미지의 `libc.so.6`을 바꿀 수 있다. 프로젝트 라이선스(Apache-2.0)는 수정과 그 디버깅을 위한 리버스 엔지니어링을 막지 않는다 |
| GCC Runtime Library Exception의 조건 | libgcc_s를 쓰는 서버 바이너리 | 충족한다고 본다(추정). 일반 GCC로 컴파일했고("Eligible Compilation Process") 서버 코드를 GPL로 낼 의무가 생기지 않는다 |

- 위 의무는 이미지를 **외부에 배포할 때** 생긴다. 배포 결정(저작권자, 공개 위치, 배포 여부)은 로드맵 "첫 배포 전 결정"에 있다. 소스 제공 방식도 그때 정한다.
- 쓰지 않는 패키지(libssl3t64, libstdc++6 등)도 이미지에 있으면 배포 대상이다. 이 패키지들은 베이스 이미지가 정한 구성이라 따로 빼지 않는다(ADR-0021).
- Rust 크레이트 고지(`cargo-about`, `THIRD-PARTY-NOTICES.txt`)는 OS 패키지를 다루지 않는다. 그래서 이미지 빌드가 베이스 이미지의 dpkg 상태에서 패키지 목록(`BASE-IMAGE-PACKAGES.tsv`)을 따로 만든다(`deploy/Dockerfile`).

## 미확인 항목

- 서버 전이 의존성 전체의 라이선스(서버 `Cargo.toml` 작성 후 확인).
- prost 등 Apache-2.0 크레이트의 `NOTICE` 파일 유무.
- MPL-2.0, EPL-2.0, LGPL과 현재 의존성의 조합별 호환성(위 표의 "추정").
- 저작권자(개인/조직)와 업무 관련성.
- glibc 이미지의 대응 소스 제공 방식(snapshot.debian.org를 가리키는 것으로 충분한지). 첫 배포 전에 정한다.

## 출처

- [S1] libwebp v1.6.0 `COPYING` — https://github.com/webmproject/libwebp/blob/v1.6.0/COPYING (확인 2026-10-23)
- [S2] libwebp v1.6.0 `PATENTS` — https://github.com/webmproject/libwebp/blob/v1.6.0/PATENTS (확인 2026-10-23)
- [S3] crates.io API, 각 크레이트 최신 안정 버전의 `license` 필드 — `https://crates.io/api/v1/crates/<이름>/<버전>` (확인 2026-10-23)
- [S4] Maven Central POM의 `<licenses>`(없으면 부모 POM) — `https://repo1.maven.org/maven2/<group>/<artifact>/<버전>/` (grpc 1.85.1, protobuf-parent 4.36.2, netty-parent 4.2.17.Final, guava-parent 33.7.2-jre, testcontainers 2.0.5, jmh-parent 1.37, twelvemonkeys 3.15.3, webp-imageio 0.3.3, chicory 1.7.5. 확인 2026-10-23)
- [S5] Apache Software Foundation, "GPL compatibility" — https://www.apache.org/licenses/GPL-compatibility.html (확인 2026-10-23)
- [S6] musl `COPYRIGHT` — https://git.musl-libc.org/cgit/musl/plain/COPYRIGHT (확인 2026-10-23)
- [S7] glibc `COPYING.LIB` — https://sourceware.org/git/?p=glibc.git;a=blob_plain;f=COPYING.LIB;hb=HEAD (확인 2026-10-23)
- [S8] Sonatype Central, "Register a Namespace" — https://central.sonatype.org/register/namespace/ (확인 2026-10-23)
- [S9] `gcr.io/distroless/cc-debian13:nonroot` index digest `sha256:08476c244888bfb425bae1ae4dd7c17f093e972aab8426883d97f3c6f280f7c1`(linux/amd64 manifest `sha256:65af2e9e44e71de2df28245f6814301775d3ef04436b7d563f240d86fedf24aa`)을 `docker export`로 꺼내 `/var/lib/dpkg/status.d/`, `/usr/share/doc/*/copyright`, `/usr/share/common-licenses/`를 확인했다 (확인 2026-11-02)
- [S10] distroless `README.md`("Distroless images are based on Debian 13 (trixie)"), `examples/rust/Dockerfile`(런타임 `gcr.io/distroless/cc-debian13`) — https://github.com/GoogleContainerTools/distroless (확인 2026-11-02)
- [S11] Debian snapshot archive — https://snapshot.debian.org/package/glibc/2.41-12%2Bdeb13u4/ (확인 2026-11-02)
