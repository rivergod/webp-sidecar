# samples

테스트와 벤치마크에 쓰는 이미지를 둔다.

- 모든 파일의 출처, 라이선스, 해시를 [SOURCES.md](SOURCES.md)에 기록한다. 기록 없는 파일은 커밋하지 않는다.
- 세트 구성, 커밋 기준(크기·라이선스), manifest 방식은 [ADR-0006](../docs/adr/0006-test-samples.md)을 따른다. 후보 출처와 라이선스 조사는 [docs/research/0005-test-samples.md](../docs/research/0005-test-samples.md)에 있다.
- 생성 환경(구현 기본값): libwebp 1.6.0 CLI를 소스에서 빌드한 Docker 이미지 안에서 Python(표준 라이브러리) 스크립트로 생성한다. 서버와 같은 libwebp 버전으로 기대값을 만들기 위해서다(로컬 1.5.0 도구는 쓰지 않는다).

## 구성

```
samples/
  manifest.json          기계용 단일 원본(경로, sha256, 크기, 출처, 라이선스, 태그, 생성 도구, 기대값, RFC 절)
  SOURCES.md             manifest.json에서 생성하는 사람용 표. 직접 고치지 않는다
  conformance.lock.json  libwebp-test-data 파일별 sha256 잠금(커밋)
  generated/             커밋. 자체 생성
    inputs/              합성 PNG(인코더 입력·애니메이션 프레임), meta/에 EXIF·XMP 페이로드
    still/               lossy, lossless, alpha, 1x1·홀수 크기, 3840x2160, 폭 16383/16384
    anim/                blend x dispose 4조합, loop, duration 경계, 혼합 코덱, 오프셋, 1프레임
    meta/                ICCP/EXIF/XMP 조합, unknown 청크, 청크 배치·중복
  malformed/             커밋. generated/를 시드로 tamper.py가 만든 손상·악성 입력
  third_party/           커밋. 재배포가 명시된 것만(compact-icc/: Compact-ICC-Profiles CC0 프로파일 + LICENSE)
  scripts/               Dockerfile, build.sh, *.py, webp_probe.c
  conformance/           커밋하지 않음. libwebp-test-data(커밋 06ddd96e)
  bench/                 커밋하지 않음. 벤치 합성 세트
  .build/                커밋하지 않음. 단계 사이의 중간 기록(*.json)
```

ADR-0006 구성의 `generated/expected/`는 따로 두지 않았다. 기대값은 manifest.json의 각 항목 `expected`에 들어 있다(같은 값을 두 곳에 두지 않기 위해서다).

## 다시 만들기

모든 커밋 출력은 도구 이미지([scripts/Dockerfile](scripts/Dockerfile): debian trixie digest 고정, libwebp 1.6.0 tarball sha256 확인)에서 만든다. 저장소 루트에서 실행한다. `-u`로 호출한 사용자 권한으로 돌려 파일이 root 소유가 되지 않게 한다.

```sh
docker build -t webp-samples-tools:libwebp-1.6.0 samples/scripts
docker run --rm --network none -u "$(id -u):$(id -g)" -v "$PWD:/repo" -w /repo \
  webp-samples-tools:libwebp-1.6.0 samples/scripts/build.sh          # generate -> tamper -> expected -> manifest -> check
```

- 단계별 실행: `python3 -I samples/scripts/{generate,tamper,expected,manifest}.py` (이 순서, 도구 이미지 안).
- 결과는 결정적이다(고정 입력·시드, 타임스탬프 없음). 같은 이미지에서 두 번 만들면 모든 파일과 manifest.json이 바이트 단위로 같다(2026-10-23 확인).
- 스크립트는 libwebp 도구 버전이 1.6.0이 아니면 멈춘다. 호스트의 다른 버전으로 커밋 출력을 만들지 않기 위해서다.
- 검사만(도구 이미지 없이 표준 Python 3. 3.13과 3.14에서 확인, Windows는 미확인): `python3 -I samples/scripts/manifest.py --check`. manifest의 sha256·크기, 목록에 없는 파일, 파일당 64 KiB·합계 5 MiB, 라이선스, SOURCES.md 최신 여부를 본다.

## 적합성 데이터 받기 (libwebp-test-data)

```sh
docker run --rm -u "$(id -u):$(id -g)" -v "$PWD:/repo" -w /repo \
  webp-samples-tools:libwebp-1.6.0 samples/scripts/build.sh conformance --check-pam-md5
# 또는 Python만으로(md5 비교 없이): python3 -I samples/scripts/fetch_conformance.py
```

- 커밋 `06ddd96e276c2c638a72d39d3c0f340afd61978c`의 gitiles tar.gz를 새 임시 디렉터리에 받아 풀고, 155개 파일을 [conformance.lock.json](conformance.lock.json)의 sha256과 비교한다. 빠진·남는·다른 파일이 하나라도 있으면 설치하지 않고 실패한다. 이미 받은 파일만 다시 검사: `--verify-only`.
- 잠금 파일을 만들 때(2026-10-23) 풀어 낸 155개 파일의 git blob SHA-1이 GitHub 미러(webmproject/libwebp-test-data)의 같은 커밋 tree와 모두 일치했다.
- **비교에는 `libwebp_tests.md5`의 `.pam` 줄만 쓴다.** `.pgm` 줄은 아직 릴리스되지 않은 libwebp 변경으로 갱신돼 있다([research/0005](../docs/research/0005-test-samples.md)). 도구 이미지의 dwebp 1.6.0으로 확인한 결과(2026-10-23): `.pam`·`.ppm`·`.bmp`·`.tiff` 96/96 일치, `.pgm` 89/96(불일치 7개는 모두 lossless 파일).
- 라이선스는 미확인이다(LICENSE 파일 없음, BSD-3-Clause 추정). 그래서 커밋하지 않는다.

## 벤치 합성 세트

```sh
docker run --rm --network none -u "$(id -u):$(id -g)" -v "$PWD:/repo" -w /repo \
  webp-samples-tools:libwebp-1.6.0 samples/scripts/build.sh bench          # --xxl 을 붙이면 8192x8192 포함
```

- `bench/synthetic/`에 크기 클래스 XS 64², S 256², M 1024×768, L 1920×1080, XL 4000×3000(XXL 8192²는 `--xxl`) × 모드 lossy q75 / lossless / lossy+alpha q75와 원본 PNG(RGB, RGBA)를 만든다. 목록과 sha256은 `bench/synthetic/index.json`.
- 내용은 그래디언트 + 결정적 노이즈(SHAKE-256, 시드 `--seed`)다. XS~XL 25개 파일, 약 136 MB, 이 호스트에서 약 13초(2026-10-23).
- 사진 샘플은 Phase 4에서 추가한다. public domain 또는 CC0로 명시된 개별 파일만 받아서 쓰고, URL과 sha256을 고정한다.

## manifest.json 스키마 (schema_version 1)

최상위: `reference_decoder`(libwebp 버전, tarball sha256, 도구 이미지), `pixel_format`, `expected_methods`(기대값 계산 방법 설명), `policy`(커밋 기준), `external`(커밋하지 않는 세트), `files`(경로순 정렬).

`files[]` 항목:

| 필드 | 내용 |
|---|---|
| `path` | samples/ 기준 POSIX 경로 |
| `sha256`, `size` | 파일 무결성 (바이트) |
| `origin` | `type`: `generated`(실행 명령 `commands[]` 또는 직접 조립 단계 `step`) / `tampered`(`seed`, `change`) / `download`(`url`, `repo`, `commit`, `repo_path`) |
| `license` | SPDX. 자체 생성은 `Apache-2.0`(프로젝트 라이선스), third_party는 `CC0-1.0` |
| `tags` | still, anim, lossy, lossless, alpha, icc, exif, xmp, malformed, resource 등 |
| `generator` | `script`, `tools`(libwebp 도구), `libwebp` 버전, `image`(도구 이미지) |
| `rfc` | RFC 9649 절(예: `§2.7.1.1`) |
| `expected` | 아래 |
| `notes`, `source` | 선택. 비고와 입력 파일 경로 |

`expected`:

- 정지 이미지: `features`(WebPGetFeatures: width, height, has_alpha, has_animation, format = lossy/lossless/mixed), `decode`(width, height, `rgba_bytes`, `rgba_sha256`, lossless이고 입력과 크기가 같으면 `matches_source_rgba`).
- 애니메이션: `features`, `animation`(canvas, loop_count, bgcolor, frame_count, `frames[]`: 오프셋·크기·duration_ms·dispose·blend·`timestamp_ms`·`rgba_sha256`, `anim_dump_matches`, 다르면 프레임별 `anim_dump_rgba_sha256`).
- 메타데이터가 있으면 `metadata`(`icc`/`exif`/`xmp`: count, 첫 청크의 size·sha256, 중복이면 `all[]`, `webpmux_get`).
- PNG 입력: `decode`(생성기가 만든 픽셀의 RGBA8 해시. RGB PNG는 A=255).
- 손상 입력: `kind`(corrupt / resource / spec-edge), `decoder`(still / anim), `outcome`(accept / reject), `observed`(features, demux, webpinfo, dwebp, anim_decoder, anim_dump 각각의 결과), `exceeds_default_limits`(grpc-api.md §3 기본 상한 중 넘는 것), resource면 `outcome_with_8gib`, 받아들여진 작은 파일이면 `decode` 해시.

## 기대값 계산

- RGBA8 = ADR-0004 와이어 포맷(비-premultiplied, stride = width × 4). 정지 이미지는 `dwebp -pam`의 헤더를 떼어 낸 바이트의 SHA-256이고, `dwebp -noasm`과 `webp_probe still`(WebPDecode, MODE_RGBA, 기본 옵션 = fancy upsampling)로 다시 계산해 같은지 확인한다.
- 애니메이션은 `webp_probe anim`(WebPAnimDecoder, MODE_RGBA — 서버가 쓸 호출)의 합성 캔버스를 프레임마다 해시한다. **anim_dump 출력은 기대값으로 쓰지 않는다.** anim_dump는 저장 전에 alpha=0 픽셀을 (0,0,0,0)으로 지운다(libwebp 1.6.0 `examples/anim_util.c` `CleanupTransparentPixels`). 그 정규화를 한 값과 anim_dump 출력이 같은지는 매번 확인한다. 현재 5개 애니메이션이 이 때문에 anim_dump와 다르다.
- `webp_probe`([scripts/webp_probe.c](scripts/webp_probe.c))는 도구 이미지 빌드 때 libwebp 1.6.0 정적 라이브러리로 컴파일하는 보조 도구다. 테스트 데이터 생성용이며 서버 구현과 무관하다.
- 손상 입력의 판정은 RLIMIT_AS 1 GiB(서버 컨테이너 기본 메모리, ADR-0012), 제한 시간 60초에서 실행한 결과다. kind=resource는 8 GiB / 180초로 한 번 더 실행한다.

## 관찰한 libwebp 1.6.0 동작 (RFC 9649와 다른 점 포함)

도구 이미지에서 실행해 확인했다(2026-10-23). 파일별 상세는 manifest.json `expected.observed`에 있다.

- **ANIM 배경색 무시**: 캔버스 초기화와 dispose-to-background를 투명 (0,0,0,0)으로 한다. 배경색을 자홍으로 둔 `anim/bd_*_dispose-bg.webp`에서 dispose된 영역이 (0,0,0,0)임을 확인했다. RFC §2.7.1.1은 배경색을 hint로 둔다. 기대값은 libwebp 동작을 따른다.
- **ALPH 예약 비트**(`malformed/alph_reserved_bits.webp`): RFC §2.7.1.2는 무시하라(MUST ignore)고 하지만 libwebp는 거부한다. 상태 코드는 `VP8_STATUS_OUT_OF_MEMORY`다(ALPH 압축 방식 2도 같다). grpc-api.md 오류 표대로 매핑하면 손상 입력이 `RESOURCE_EXHAUSTED`로 보고된다.
- **VP8X 예약 비트**: 플래그 바이트의 예약 비트(0xC1)는 WebPDecode/dwebp는 무시하지만 **WebPDemux(따라서 WebPAnimDecoder)는 거부한다**. 24비트 예약 필드는 둘 다 무시한다. RFC §2.7은 둘 다 무시하라고 한다.
- **청크 순서 위반**(RFC §2.7 "SHOULD fail"): ALPH가 VP8 뒤 → dwebp는 alpha까지 디코딩, WebPDemux는 거부. ICCP가 이미지 뒤 → 둘 다 받음(webpinfo는 오류). VP8 중복 → dwebp는 받음, WebPDemux는 거부.
- **중복 메타데이터**(`meta/duplicate_exif.webp`, `duplicate_iccp.webp`): WebPDemux와 dwebp는 받고 첫 청크를 돌려준다(RFC §2.7.1.4, §2.7.1.5의 MAY). WebPMux(`webpmux -get`)는 파일 자체를 거부한다.
- **ANMF 크기 불일치·중첩**: ANMF가 24×24라고 선언하고 비트스트림이 16×16이어도, ANMF 안에 ANMF를 넣어도 WebPDemux/WebPAnimDecoder가 받는다(webpinfo는 오류).
- **크기 필드**: simple 형식에서 VP8 청크 크기를 실제보다 100 B 작게 적어도 dwebp는 원본과 같은 픽셀로 디코딩한다(WebPDemux는 거부). 홀수 청크 뒤 패딩이 빠지면(이미지 뒤 메타데이터) dwebp는 받고 WebPDemux는 거부한다. 0이 아닌 패딩과 RIFF 뒤의 남는 데이터(RFC §2.4 MAY ignore)는 모두 받는다.
- **크기 경계**: VP8L 헤더 폭 16384(인코더 한도 16383 초과, RFC §3.4 범위 안)를 디코더가 받는다(`still/lossless_16384x1_header_patched.webp`).
- **decompression bomb**: 32바이트 VP8L(`malformed/vp8l_bomb_16383x16383.webp`)이 16383×16383 RGBA 1,073,610,756 B로 디코딩된다. 1 GiB 상한에서는 `OUT_OF_MEMORY`, 8 GiB에서는 성공. 유효 애니메이션의 캔버스만 16384²로 바꾼 파일도 같다. 1x1 프레임 1000개 + 4096² 캔버스(`anim_frames_1000_canvas_4096.webp`, 약 50 KB)는 서버 기본 상한 안이고 1 GiB에서도 디코딩되지만, 프레임마다 64 MiB 캔버스를 합성한다(합계 약 64 GiB 출력, 이 호스트에서 출력 없이 약 6초).
- **webpmux 동작**: 홀수 프레임 오프셋은 짝수로 내린다((7,5) → (6,4)). 캔버스 크기를 지정하지 않으면 프레임 1개짜리 애니메이션을 비애니메이션 파일로 바꾼다(`src/mux/muxedit.c` `MuxCleanup`). 그래서 `anim/single_frame.webp`는 직접 조립했다.

## 미확인

- libwebp-test-data 데이터 파일의 라이선스.
- 도구 이미지의 apt 패키지 버전(고정하지 않음). 커밋 출력은 libwebp만 결정하므로 영향이 없다고 본다(추정). libpng는 PNG 입력을 읽을 때만 쓴다.
- 다른 CPU 아키텍처(arm64)에서 인코더 출력이 같은지. 커밋한 파일은 linux/amd64에서 만들었다. 디코딩 기대값은 `-noasm`과 SIMD 경로가 같음을 amd64에서만 확인했다.
