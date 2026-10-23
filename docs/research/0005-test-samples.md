# 0005. 테스트 샘플 세트 후보와 라이선스

- 상태: 초안
- 작성일/확인일: 2026-10-23
- 관련 ADR: ADR-0006

## 요약

- 공식 적합성 벡터의 1차 소스는 libwebp-test-data(`06ddd96`, 2025-08-26) 하나다. 정지 이미지만 들어 있다. `.webp` 131개의 구성은 simple lossy(VP8) 70개, simple lossless(VP8L) 43개, VP8X+ALPH+VP8 18개다. **애니메이션, ICCP, EXIF, XMP 샘플은 하나도 없다** [S1].
- 판정 방식은 두 가지다. 첫째, `dwebp`로 bmp/pam/pgm/ppm/tiff 5형식을 출력한 뒤 `libwebp_tests.md5`(96파일×5형식=480줄)와 md5를 비교한다. 둘째, lossless 벡터 35개는 참조 이미지(grid/peak/lossless_color_transform)와 `cmp`로 바이트 단위 비교한다 [S1].
- 로컬 dwebp 1.5.0으로 다시 돌려 보니 480개 중 473개가 일치했다. 불일치 7개는 모두 lossless→PGM(YUV 출력) 항목이다. 이 md5는 2025-08-26에 갱신됐는데, 원인인 libwebp 커밋 `0d14d84b`(2025-08-25)가 어떤 릴리스 태그에도 아직 들어 있지 않다. JVM 쪽은 PAM(RGBA)과 PPM(RGB) md5만 쓰면 이 문제를 피한다 [S1][S2].
- test-data 저장소에는 LICENSE 파일이 없다. 스크립트 헤더에만 "libwebp와 같은 조건"이라고 적혀 있어서 **데이터 파일의 라이선스는 미확인**이다(BSD-3-Clause로 추정). 그래서 저장소에 넣기(vendoring)보다 고정 커밋을 빌드 때 받는 방식을 권장한다. Wuffs가 이렇게 한다 [S27].
- 애니메이션(blend/dispose 4조합), ICC, EXIF, XMP는 자체 생성할 수 있다. 로컬 libwebp 1.5.0 도구와 Pillow로 scratchpad에서 실제로 만들어 봤다. 라이선스 문제가 없다. 결정적인 blend/dispose 조합은 `webpmux -frame`으로만 지정할 수 있다. img2webp와 gif2webp는 인코더가 자동으로 고른다 [S5].
- 공개된 malformed WebP 전용 코퍼스는 찾지 못했다. OSS-Fuzz의 누적 코퍼스는 프로젝트 담당자만 받을 수 있다 [S4]. CVE-2023-4863 PoC 저장소에는 라이선스가 없으므로 커밋하지 않는다 [S21]. 손상 입력은 자체 변조 스크립트로 만든다.
- RFC 9649와 libwebp 구현이 어긋나는 지점을 하나 확인했다. RFC는 ALPH 헤더의 Rsv 비트를 "Readers MUST ignore"라고 정하는데, libwebp는 이를 오류로 처리한다. VP8X Rsv 비트는 무시한다(1.5.0에서 실행 확인, main 소스 확인) [S2][S6].
- 298바이트짜리 유효 애니메이션의 VP8X 캔버스만 16384×16384로 바꿔도 1 GiB 캔버스 할당을 유도할 수 있다. 서버에 픽셀 수 상한이 필요하다는 근거다(실행 확인).

## 후보 출처별 정리

| 출처 | 내용 | 라이선스 | 크기 | 용도 | 출처링크 |
|---|---|---|---|---|---|
| libwebp-test-data `06ddd96` | `.webp` 131개(VP8 70 / VP8L 43 / VP8X+ALPH 18). ALPH filter 0~3 × method 0/1, 무압축 alpha, 1×1·13×1·1×13·31×13 경계 크기, lossless 변환 조합 16종×2, VP8 테스트 벡터(vp80-00~05) 56개. 참조 이미지 bmp/pam/pgm/ppm/tiff/png와 md5 매니페스트, `test_dwebp.sh`·`test_lossless.sh`·`test_cwebp.sh`가 있다. 애니메이션·ICCP·EXIF·XMP는 없다. vp80-* 등 60개 파일은 VP8 청크 뒤에 IART/ICOP/INAM/ICMT 청크가 붙어 있다(사실상 unknown chunk 허용 테스트). | 명시 없음(**미확인**). 스크립트 헤더는 "distributed under the same terms as libwebp"라서 BSD-3-Clause로 추정한다 | 작업 트리 23.5 MB, `.git` 20 MB. 100 KB 미만 128개의 합은 1.3 MB다. 대형 파일: `bryce.webp` 3.4 MB(11158×2156 lossy), `lossless_big_random_alpha.webp` 14 MB(2048² lossless+alpha) | 적합성(주), 경계값, 벤치용 대형 실파일 | [S1] |
| libwebp 저장소 `1844042` | `examples/test.webp`(4,880 B, 128×128 lossy)와 `test_ref.ppm`, `webp_js/test_webp_{js,wasm}.webp`, `tests/fuzzer/*.cc`, `fuzz.dict`, `img_{alpha,grid,peak}.h`(헤더에 이미지 내장). 코퍼스 파일은 없다 | BSD-3-Clause(`COPYING`)와 `PATENTS` 특허 허여 | 수 KB | 스모크 테스트, fuzz dict 참고 | [S2] |
| OSS-Fuzz libwebp | 시드 코퍼스는 빌드 시 libwebp-test-data에서 32 KB 미만 `.webp`를 zip으로 묶어 만든다. 누적 코퍼스와 백업은 `project.yaml` 담당자 계정만 받을 수 있다 | 시드는 test-data와 같다. 누적 코퍼스는 비공개다 | — | 사용하지 않는다(시드는 test-data로 대체한다) | [S3][S4] |
| PngSuite 2017jul19 | PNG 176개. 색상형 0/2/3/4/6, 1~16 bit, Adam-7, 크기 1~9·32~40, tRNS, gAMA, cHRM, sBIT, pHYs, tEXt/zTXt/iTXt, eXIf(1개), 손상 14개. **iCCP와 sRGB 청크는 없다**(파일 검사로 확인) | "Permission to use, copy, modify and distribute these images for any purpose and without fee is hereby granted." (c) Willem van Schaik | tgz 68,521 B(sha256 `0294b244…bfdc8`), 풀면 약 716 KB | 인코더 입력. ImageIO PNG reader가 만드는 여러 `BufferedImage` 타입(16-bit gray, indexed, gray+alpha)을 거치는 변환 경로를 테스트한다. ICC 용도로는 쓸 수 없다 | [S9] |
| Google WebP gallery1 | 사진 5장(JPEG/WebP/PNG, 폭 1024) | 페이지 본문은 CC BY 4.0이지만 이미지마다 예외가 있다. 1~3번은 "reproduced with permission"이라 우리가 재배포할 권한은 미확인이다. 4~5번은 CC BY-SA 3.0이다 | 장당 WebP 30~200 KB | 사진 벤치. 커밋은 권장하지 않는다 | [S10] |
| Google WebP gallery2 | lossless/alpha 5장 | 1~2번은 public domain, 3·5번은 CC BY-SA 3.0, 4번(Google Doodle)은 조건 미확인이다 | 소형 | 알파 사진 샘플(1~2번만 부담 없음) | [S11] |
| Kodak (kodim01~24) | 768×512 PNG 24장 | 1차 출처(r0k.us)는 HTTP 401로 확인하지 못했다. 2차 출처(libavif README)는 "released by the Eastman Kodak Company for unrestricted usage"라고 적는다. **미확인** | 장당 약 0.5 MB(추정) | 중간 크기 사진 벤치 후보 | [S12] |
| DIV2K | 2K 해상도 1000장 | "made available for academic research purpose only", 저작권은 원소유자에게 있다 | 수 GB(추정) | 이 저장소에는 부적합하다 | [S13] |
| CLIC | TFDS 2020판 기준 test 428 / train 1,633 / validation 102 | 데이터 라이선스 문구를 찾지 못했다(**미확인**) | 7.48 GiB | 대형 사진 벤치 후보. 라이선스 확인 전까지 보류한다 | [S14] |
| Wikimedia Commons | 파일 단위 | 파일마다 다르다(CC0, PD, CC BY, CC BY-SA 등. NC/ND는 받지 않는다) | — | 필요한 파일만 골라 쓰고, 파일별로 출처와 라이선스를 기재한다 | [S15] |
| ICC sRGB 프로파일(color.org) | `sRGB2014.icc`(v2), `sRGB_v4_ICC_preference.icc` 등 | ICC가 저작권을 가진 프로파일은 "may be copied, distributed, embedded, made, used, and sold without restriction"이다(변경본은 식별·저작권 정보를 지워야 한다). v4 preference는 파일을 바꾸지 않는다는 조건이 붙은 허가다 | 수 KB(추정) | 실제 ICCP 페이로드 | [S16][S17] |
| Compact-ICC-Profiles `bdd8466` | sRGB, Display P3, AdobeCompat, gray, Rec.2020 등 290~790 B(CMYK 1종 8,464 B) | CC0-1.0(`license` 파일) | 수백 B | 작은 ICCP를 여러 종류로. 커밋 가능 | [S18] |
| exif-samples `5332a9c`(보관 처리됨) | jpg/tiff/heic EXIF 샘플 | README에 따르면 기여 이미지는 CC BY-SA 4.0이다. 개별 파일의 출처는 불명확하다 | — | 참고만 하고 커밋하지 않는다 | [S19] |
| ExifTool sample images | 7,119개 기종의 JPEG(본문 이미지는 교체, APP 세그먼트는 유지) | 라이선스 명시가 없다(**미확인**) | tar.gz | EXIF 파서 다양성 참고만 | [S20] |
| TwelveMonkeys imageio-webp 테스트 리소스 | `.webp` 31개(test-data 일부, gallery 파생, `animated-webp-supported.webp`, `photo-iccp-adobergb.webp` 등) | 저장소는 BSD-3-Clause다. 개별 이미지는 원출처 라이선스를 따른다(추정) | — | 비교 대상 라이브러리의 동작 확인용 참고 | [S24] |
| image-rs/image-webp tests | gallery1/2, ImageMagick으로 만든 애니메이션, dwebp 출력 참조 PNG(기본과 `-nofancy`를 따로 둠) | 이미지는 원출처를 따른다. 저장소 라이선스는 미확인 | — | "참조 = libwebp 출력"으로 테스트한 선례 | [S26] |

## 자체 생성 샘플

### 로컬 도구 (2026-10-23 확인)

- `/usr/bin/{cwebp,dwebp,img2webp,webpmux,gif2webp,webpinfo,anim_dump,anim_diff,vwebp}`가 모두 있다. Ubuntu `webp 1.5.0-0.1build1` 패키지이고 `cwebp -version`은 `1.5.0`, libsharpyuv `0.4.1`이다.
- ImageMagick(`convert`/`magick`)과 `exiftool`은 없다.
- 시스템 Python에 Pillow 12.1.1(LittleCMS 2.17)이 있어 합성 PNG/GIF, ICC, EXIF 입력을 만들 수 있다. LittleCMS로 만든 sRGB 프로파일의 cprt 태그는 "No copyright, use freely"다.

### 도구별 주요 플래그

근거는 libwebp `1844042`의 `man/*.1`이다 [S5]. 아래 표의 동작은 로컬 1.5.0에서 실행해 확인했다.

| 도구 | 플래그 | 효과 |
|---|---|---|
| cwebp | `-q`, `-m 0..6`, `-lossless`, `-z 0..9`, `-near_lossless N` | lossy 품질, 노력도, lossless |
| cwebp | `-exact` | 투명 영역의 RGB를 보존한다(lossless 왕복 테스트에 필수) |
| cwebp | `-alpha_q`, `-alpha_method 0\|1`, `-alpha_filter none\|fast\|best`, `-noalpha` | ALPH 압축. 0은 무압축, 1은 lossless. 필터 선택 |
| cwebp | `-metadata all\|none\|exif\|icc\|xmp` (기본 `none`) | 입력 PNG/JPEG의 메타데이터를 복사한다 |
| webpmux | `-set icc\|exif\|xmp FILE`, `-strip …`, `-get frame N` | 메타데이터 청크를 넣거나 빼고, 프레임을 추출한다 |
| webpmux | `-frame F +d[+x+y[+m[b]]]`, `-loop 0..65535`, `-bgcolor A,R,G,B` | 프레임마다 dispose(`m`: 0=NONE, 1=BACKGROUND)와 blend(`+b`=BLEND, `-b`=NO_BLEND)를 지정한다. 입력은 비애니 WebP여야 한다. 홀수 오프셋은 짝수로 내린다(`examples/webpmux.c`, `src/mux/muxedit.c`) |
| img2webp | 파일 옵션 `-loop`, `-min_size`, `-kmin/-kmax`, `-mixed` / 프레임 옵션 `-d ms`(기본 100), `-lossless\|-lossy`, `-q`, `-m`, `-exact\|-noexact` | lossy/lossless 프레임을 섞을 수 있다. blend/dispose는 WebPAnimEncoder가 정한다 |
| gif2webp | `-lossy`, `-mixed`, `-q`, `-m`, `-min_size`, `-kmin/-kmax`, `-metadata all\|none\|icc\|xmp`(기본 `xmp`), `-loop_compatibility` | GIF의 loop count에 1을 더해 WebP 의미로 바꾼다(`examples/gif2webp.c` 492행 이후) |
| anim_dump | `-folder`, `-prefix`, `-pam\|-tiff` | 합성이 끝난 캔버스를 프레임마다 저장한다. 애니메이션 기대값을 만들 때 쓴다 |
| dwebp | `-pam\|-ppm\|-bmp\|-tiff\|-pgm\|-yuv`, `-nofancy`, `-nofilter`, `-noasm`, `-mt` | 정지 이미지 기대값을 만든다. 애니메이션은 디코드를 거부한다 |
| webpinfo | `-summary`, `-quiet` | 청크 구조를 검사한다 |

### 실행 검증 결과 (scratchpad)

| 산출물 | 명령 | 결과 구조 |
|---|---|---|
| simple lossy | `cwebp -q 75` | `VP8` |
| lossless+alpha | `cwebp -lossless -exact` | `VP8L`(alpha_is_used=1) |
| lossy+alpha | `cwebp -alpha_method 1 -alpha_filter best` / `-alpha_method 0` | `VP8X(L) ALPH VP8 `. 무압축 ALPH는 256×192에서 50 KB |
| ICC(입력에서 복사) | PNG iCCP → `cwebp -metadata icc` | `VP8X(I) ICCP VP8 ` |
| ICC+EXIF+XMP | `webpmux -set icc`, `-set exif`, `-set xmp`를 차례로 | `VP8X(I,E,X) ICCP VP8 EXIF XMP ` |
| 애니메이션(조합 지정) | `webpmux -frame f0 +100+0+0+0-b -frame f1 +150+8+8+1+b -frame f2 +200+16+16+0+b -frame f3 +250+24+24+1-b -loop 3 -bgcolor 128,255,0,0` | ANMF 4개에 (blend, dispose) 4조합이 의도대로 기록됐다. 캔버스는 프레임 합집합인 88×88이다 |
| 애니메이션(혼합) | `img2webp -lossless … -lossy -q 70 …` | ANMF 안에 `VP8L`과 `ALPH/VP8 `가 섞인다. 4프레임 모두 blend=no가 선택됐다 |
| 애니메이션(GIF) | `gif2webp` | GIF loop 3이 WebP loop 4가 된다 |
| 합성 기대값 | `anim_dump -pam` | 프레임별 합성 캔버스 PAM 4장 |

### 생성할 때 유의점

- **EXIF 페이로드 형태.** libwebp imageio는 JPEG APP1에서 `Exif\0\0` 6바이트를 떼고 TIFF 헤더부터 저장한다(`imageio/jpegdec.c` 158~186행) [S2]. 접두어가 붙은 변형도 따로 샘플로 둔다. 그렇게 쓰는 작성기가 있다고 보지만 추정이다.
- **애니메이션 기대값은 libwebp 동작을 기준으로 한다.** WebPAnimDecoder(`anim_dump`)는 ANIM 배경색을 무시하고 캔버스를 투명으로 초기화한다. dispose-to-background도 투명으로 채운다(`src/demux/anim_decode.c` `ZeroFillCanvas`, `ZeroFillFrameRect`). 블렌딩은 선형화 없이 정수로 근사한다(`BlendChannelNonPremult`) [S2]. RFC는 배경색을 hint로 두고(§2.7.1.1) 선형 공간 블렌딩을 SHOULD로 둔다. 따라서 매니페스트에 "기준 구현 = libwebp x.y.z"를 적는다. libwebp가 아닌 디코더와 비교할 때는 채널당 ±1 같은 허용오차를 둔다(추정).
- **lossy 디코드 결과는 업샘플링 방식에 따라 다르다.** 로컬 dwebp에서 기본(fancy)과 `-nofancy`의 PAM이 달랐다. image-rs도 참조를 두 벌 둔다 [S26]. libwebp 기반 구현(사이드카, darkxanter)은 옵션이 같으면 바이트가 일치할 것으로 본다(추정). 순수 Java인 TwelveMonkeys는 PSNR 임계값으로 비교한다(testing.md §4).
- **생성 샘플은 커밋해서 고정한다.** 인코더 출력은 libwebp 버전마다 바뀔 수 있다. 1.6.0 NEWS에 "compression improvements"가 있다 [S2]. 생성 스크립트와 도구 버전을 매니페스트에 기록하고, 다시 생성하는 일은 수동 refresh 작업으로 둔다.
- darkxanter 0.3.3은 libwebp 1.3.2를 번들한다(docs/research/0001). 벤치에서 사이드카와 libwebp 버전이 다르다는 점을 결과에 적는다.

### 생성 목록 제안 (커버리지)

| 분류 | 항목 |
|---|---|
| 정지 | lossy q{0,75,100}, lossless z{0,9}, near_lossless, lossy+alpha(ALPH method 0/1), `-exact` 유무. ALPH filter 0~3 조합은 test-data가 이미 다룬다 |
| 크기 경계 | 1×1, 1×N, N×1, 홀수 크기, 16383×1(VP8 최대 폭), 16384×1(VP8L 최대 폭). 가늘고 긴 이미지는 생성 비용이 작다 |
| 애니메이션 | blend{0,1}×dispose{0,1}, 부분/전체 프레임, 알파 유무 프레임, lossy/lossless 혼합, loop {0,1,65535}, duration {0,1,10,100,2^24−1}, 1프레임 애니메이션, 캔버스 끝에 맞춘 오프셋 |
| 메타데이터 | ICCP(LittleCMS sRGB, CC0 Display P3·gray), EXIF(II/MM, Orientation 1~8, `Exif\0\0` 접두어 유무), XMP(xpacket 래퍼 유무), 조합, 애니메이션+메타 |
| 청크 배치 | unknown chunk(파일 끝, ANMF 끝 — §2.7.1.6), 메타데이터를 이미지 앞에 두기(MAY — §2.7), ICCP/EXIF 중복(첫 번째만 써도 됨 — §2.7.1.4, §2.7.1.5) |

## 손상/악성 입력

### 공개 코퍼스 현황

- malformed WebP 전용 공개 코퍼스는 웹 검색으로 찾지 못했다(미확인).
- OSS-Fuzz의 시드는 test-data 중 32 KB 미만 파일이고, 누적 코퍼스와 백업은 비공개다 [S3][S4]. libwebp `tests/fuzzer`에는 퍼저 소스와 `fuzz.dict`만 있다 [S2].
- CVE-2023-4863은 커밋 `902bc919`(2023-09-07, "Fix OOB write in BuildHuffmanTable")에서 고쳐졌고 v1.3.2(2023-09-13)에 들어갔다 [S23]. 공개 PoC로 mistymntncop/CVE-2023-4863 `cb9f7aa`(`craft.c`, `bad.webp`, `nso.webp`)이 있다. 라이선스가 없으므로 재배포 권한이 없고, 따라서 커밋하지 않는다 [S21]. 분석 글은 [S22]다.
- 권장안: 손상 입력은 자체 생성한 유효 파일을 시드로 삼아 변조 스크립트로 만든다. CVE 회귀 테스트는 선택 사항으로 둔다. 하려면 고정 커밋과 sha256으로 로컬에 받아 사이드카 샌드박스 안에서만 실행한다.

### 변조 생성 검증 (scratchpad, dwebp/anim_dump 1.5.0)

| 케이스 | 변조 | RFC | libwebp 1.5.0 결과 |
|---|---|---|---|
| 잘림 11 B / 절반 / 마지막 1 B 제거 | 잘라내기 | §2.4 | `NOT_ENOUGH_DATA`(7) |
| RIFF 크기 `0xFFFFFFF0` | File Size | §2.4 | `NOT_ENOUGH_DATA` |
| RIFF 크기 20 | File Size | §2.4 | `BITSTREAM_ERROR`(3) |
| 청크 크기 `0xFFFFFFFF` | Chunk Size | §2.3 | `BITSTREAM_ERROR` |
| FourCC `WEBQ` | 헤더 | §2.4 | `BITSTREAM_ERROR` |
| VP8X 캔버스 2^24×2^24 | 크기 필드 | §2.7 | `BITSTREAM_ERROR` |
| VP8X 캔버스 65536×65536(면적 2^32) | 크기 필드 | §2.7 (W×H ≤ 2^32−1) | `BITSTREAM_ERROR` |
| VP8X 캔버스 1×1, 비트스트림 256×192 | 불일치 | §2.7, §2.5 | `BITSTREAM_ERROR` |
| VP8L 시그니처 `0x2e` / version 7 / 16384² 선언 | VP8L 헤더 | §3.4 | `BITSTREAM_ERROR` |
| ALPH Rsv 비트 = 3 | ALPH 헤더 | §2.7.1.2 "Readers MUST ignore" | **디코드 실패**(`OUT_OF_MEMORY`로 보고됨). RFC와 다르다. main에서도 `src/dec/alpha_dec.c` 84~89행이 `rsrv != 0`이면 거부한다. 2026-08-11 커밋 `fae4f3f`가 실패 사유 보고 방식을 바꿨다 |
| VP8X Rsv 비트 설정 | VP8X 플래그 | §2.7 "Readers MUST ignore" | 정상 디코드 |
| 유효 애니메이션(298 B)에서 VP8X 캔버스만 16384² | 크기 필드(유효 범위 안) | §2.7.2 (프레임이 캔버스 안이므로 유효) | 1 GiB 캔버스를 할당하려 한다. 가상 메모리 600 MB 제한에서 `anim_dump`가 실패했다 |

### 추가로 만들 케이스

- 청크 순서 위반(ICCP가 VP8 뒤, ALPH가 VP8 뒤 — §2.7 "Readers SHOULD fail"), VP8 중복, ANMF 안의 ANMF, A 플래그 없는 ANMF, A 플래그는 있는데 ANIM이 없음, 프레임 0개, 프레임 오프셋이 캔버스 밖.
- 홀수 크기 청크의 패딩 누락이나 0이 아닌 패딩, VP8 시작 코드(`9d 01 2a`) 변조, VP8 파티션 크기가 청크보다 큼, VP8L color_cache_bits 12(§3.6.2.3 허용 범위 1..11).
- 자원 고갈은 커밋하지 않고 테스트 실행 중에 생성한다: 수십 MB짜리 ICCP/EXIF/XMP, 1×1 프레임 수만 개, 16383×16383 단색 lossless(파일은 작고 디코드에 약 1 GiB 필요, 추정).
- 기대 결과는 "오류 종류(예외 타입·gRPC 상태) + 크래시·OOM 없음 + 시간 상한"으로 매니페스트에 적는다.

## RFC 9649 절 대응표

RFC 9649는 Informational이고 2024-11에 발행됐다. HTML 앵커는 `https://www.rfc-editor.org/rfc/rfc9649.html#section-2.7.1.1` 형식이다 [S6].

| 주제 | 절 | 제목(원문) | 핵심 규칙과 한계 |
|---|---|---|---|
| 용어 | §2.2 | Terminology & Basics | uint16/24/32는 little-endian. 1-based 필드는 값−1을 저장한다. FourCC는 대소문자를 구분한다 |
| RIFF 청크 | §2.3 | RIFF File Format | Chunk Size는 헤더와 패딩을 뺀 크기다. 크기가 홀수면 패딩 1바이트를 붙이고 그 값은 MUST 0이다 |
| 파일 헤더 | §2.4 | WebP File Header | `RIFF`, File Size(최대 2^32−10, 파일 전체 최대 4 GiB−2), `WEBP`. File Size 뒤의 데이터는 SHOULD NOT이고, reader는 무시하고 파싱해도 된다(MAY) |
| simple lossy | §2.5 | Simple File Format (Lossy) | VP8 프레임 헤더의 크기가 캔버스 크기다. YUV→RGB는 Rec.601이 SHOULD다. VP8 폭·높이는 14 bit(최대 16383)다(RFC 6386 §9.1 [S8]) |
| simple lossless | §2.6 | Simple File Format (Lossless) | VP8L 헤더의 크기가 캔버스 크기다 |
| extended / VP8X | §2.7 | Extended File Format | 플래그 Rsv/I/L/E/X/A/R(예약 비트는 MUST 0, reader MUST ignore). Canvas Width/Height Minus One은 각 24 bit, **W×H ≤ 2^32−1**. 청크 순서 VP8X→ICCP→ANIM→(ANMF \| ALPH→VP8/VP8L)은 MUST이고, 순서가 틀리면 reader SHOULD fail. 메타데이터와 unknown 청크는 순서가 달라도 된다(MAY) |
| 청크 개요 | §2.7.1 | Chunks | — |
| ANIM/ANMF | §2.7.1.1 | Animation | 배경색은 [B,G,R,A] 순서이고 hint다. Loop Count는 uint16이며 0은 무한이다. A 플래그가 있으면 ANIM이 MUST다. Frame X/Y는 uint24×2, 폭·높이는 24 bit 1-based, Duration은 24 bit ms(0과 10 이하의 해석은 구현에 맡긴다). B: 0=알파 블렌딩, 1=덮어쓰기. D: 0=그대로, 1=프레임 사각형을 배경색으로. 블렌딩 공식과 선형 공간 SHOULD. Frame Data = ALPH? + 비트스트림 + unknown* |
| ALPH | §2.7.1.2 | Alpha | Rsv(2) P(2: 0/1) F(2: 0 없음, 1 수평, 2 수직, 3 그래디언트) C(2: 0 원시, 1 lossless). lossless일 때 알파는 green 채널에서 꺼낸다. VP8L 프레임에는 ALPH를 SHOULD NOT |
| 비트스트림 | §2.7.1.3 | Bitstream (VP8/VP8L) | `VP8 `(넷째 글자는 공백) 또는 `VP8L` |
| ICCP | §2.7.1.4 | Color Profile | 이미지 데이터 앞에 MUST 온다. 1개를 SHOULD로 하고, 여러 개면 첫 번째 외에는 무시해도 된다(MAY). 없으면 sRGB를 가정한다(SHOULD) |
| EXIF/XMP | §2.7.1.5 | Metadata | 종류마다 1개를 SHOULD로 하고, 여러 개면 첫 번째만 써도 된다(MAY). `XMP `의 넷째 글자는 공백이다 |
| unknown | §2.7.1.6 | Unknown Chunks | 파일 끝 또는 ANMF 끝에 올 수 있다. reader는 무시를 SHOULD, writer는 원래 순서대로 보존을 SHOULD |
| 캔버스 조립 | §2.7.2 | Canvas Assembly from Frames | 의사코드(MUST). 루프마다 캔버스를 지우고, 이전 프레임의 dispose를 적용한 뒤 렌더링한다. 프레임은 캔버스 안에 MUST 있어야 한다. 프레임마다 ALPH와 비트스트림은 각각 1개다 |
| 레이아웃 예시 | §2.7.3 | Example File Layouts | lossy+alpha, lossless+unknown, ICC+XMP, 애니메이션+EXIF |
| VP8L 헤더 | §3.4 | RIFF Header | 시그니처 `0x2f`, 폭·높이 14 bit+1(최대 16384×16384). alpha_is_used는 hint다. version은 MUST 0이고, 다른 값이면 오류다 |
| VP8L 변환 | §3.5 (§3.5.1~3.5.4) | Transforms | Predictor / Color / Subtract Green / Color Indexing |
| 이미지 데이터 | §3.6, §3.6.2.3 | Image Data / Color Cache Coding | color_cache_code_bits는 [1..11] |
| 엔트로피 | §3.7 | Entropy Code | prefix code 구성(CVE-2023-4863이 있던 영역) |
| 전체 구조 | §3.8 | Overall Structure of the Format | ABNF 형태의 구조 요약 |
| 보안 | §4 | Security Considerations | 정수 오버플로, OOB, 자원 고갈 |
| 상호운용 | §5 | Interoperability Considerations | — |
| 미디어 타입 | §6.1 | The 'image/webp' Media Type | — |

- §2.7.2 의사코드는 `frame_right = frameX + frameWidth`라고 쓰지만, 본문은 렌더링 원점을 `(2 * Frame X, 2 * Frame Y)`라고 적는다. 코드 주석에는 "픽셀 오프셋 = 2 × Frame X (§2.7.1.1)"라고 명시하는 편이 안전하다(추정). errata 검색 결과는 "No matching errata found"였지만, 필터가 실제로 적용됐는지는 확인하지 못했다 [S7].
- 규범은 아니지만 참고할 libwebp 구현 상한(`1844042`): `MAX_CANVAS_SIZE`=1<<24, `MAX_IMAGE_AREA`=1<<32, `MAX_LOOP_COUNT`=1<<16, `MAX_DURATION`=1<<24, `MAX_POSITION_OFFSET`=1<<24(`src/webp/format_constants.h` 88~92행). `WEBP_MAX_DIMENSION`=16383(`src/webp/encode.h` 308행). `WEBP_MAX_ALLOCABLE_MEMORY`=1<<34(64-bit, `src/utils/utils.h` 38~45행). OSS-Fuzz 빌드는 `WEBP_MAX_IMAGE_SIZE=838860800`(800 MiB)을 쓴다 [S2][S3].

## 권장 구성

최종 결정은 ADR-0006에서 사람이 내린다.

```
samples/
  README.md
  SOURCES.md              # 사람이 읽는 표: 경로/출처/라이선스/SHA-256/비고 (manifest에서 생성 가능)
  manifest.json           # 기계용 단일 원본 (아래 필드)
  generated/              # 커밋한다. 자체 생성, 파일당 64 KiB 이하
    inputs/               #   합성 PNG/GIF, .icc(LittleCMS·CC0), .exif, .xmp
    still/                #   lossy / lossless / alpha / 크기 경계
    anim/                 #   blend×dispose 행렬, loop/duration 경계, 혼합 프레임
    meta/                 #   ICCP/EXIF/XMP 조합, unknown 청크, 순서 변형
    expected/             #   기대값 해시(RGBA8 SHA-256, anim_dump 프레임 해시)
  malformed/              # 커밋한다. generated/를 시드로 변조 스크립트가 만든다(작은 파일만)
  third_party/            # 선택. 재배포가 허용된 것만(PngSuite + LICENSE, Compact-ICC CC0)
  conformance/            # 커밋하지 않는다(.gitignore). test-data를 고정 커밋으로 받는다
  bench/                  # 커밋하지 않는다. 벤치 전에 생성하거나 받는다
tools/samples/            # 생성·변조·fetch·검증 스크립트 (위치는 미정)
```

- **커밋 기준(제안).** 파일당 64 KiB 이하, `samples/` 합계 5 MiB 이하. 라이선스가 자체 생성, CC0, PngSuite 허가처럼 재배포를 명시적으로 허용해야 한다. 숫자는 결정할 항목이다.
- **받거나 생성하는 것(커밋하지 않음).**
  - libwebp-test-data: `06ddd96e276c2c638a72d39d3c0f340afd61978c`, 23.5 MB, 라이선스 미확인.
  - 벤치 이미지 전체.
  - 사진 소스(Kodak, Commons).
  - 대용량 자원 고갈 입력.
  - CVE PoC(선택).
  - 받을 때는 URL과 커밋, 파일별 sha256을 manifest에 고정한다. 불일치하면 빌드를 실패시킨다. 캐시는 저장소 밖(예: `~/.cache`)에 둔다.
- **manifest 필드(제안).** `path`, `sha256`, `size`, `origin`(url+commit 또는 생성 명령), `license`(SPDX 또는 원문 요약), `tags`(lossy/lossless/alpha/anim/icc/exif/xmp/malformed/bench), `generator`(도구와 버전), `expected`(아래), `rfc`(예: `§2.7.1.1`).
- **해시 방식.**
  - 파일 무결성: SHA-256.
  - 디코드 기대값: ADR-0004 와이어 포맷과 같은 RGBA8(비-premultiplied, stride=w×4) 원시 바이트의 SHA-256과 w, h. 애니메이션은 프레임마다 같은 방식으로 기록한다(기준 구현 버전을 함께 적는다).
  - libwebp-test-data: 디코드 결과로 dwebp와 같은 PAM 바이트(`P7\nWIDTH w\nHEIGHT h\nDEPTH 4\nMAXVAL 255\nTUPLTYPE RGB_ALPHA\nENDHDR\n` + RGBA)를 만들어 `libwebp_tests.md5`의 `.pam` 줄과 MD5로 비교한다. 불투명 이미지도 DEPTH 4다(실행 확인). `.pgm` 줄은 쓰지 않는다. lossless 벡터는 `grid.pam`/`peak.pam`과 바이트 비교한다.
  - lossy를 libwebp가 아닌 디코더와 비교할 때는 PSNR 임계값을 쓴다(testing.md §4).
- **벤치 세트(제안).**
  - 크기 클래스: XS 64², S 256², M 1024×768, L 1920×1080, XL 4000×3000, XXL 8192²(opt-in).
  - 인코딩 모드: lossy q75, lossless, lossy+alpha.
  - 콘텐츠: 합성(그래디언트+노이즈), 사진.
  - 실제 대형 파일: `bryce.webp`, `lossless_big_random_alpha.webp`.
  - 합성 콘텐츠는 압축률이 비현실적이므로 사진 콘텐츠를 반드시 함께 쓴다.
  - 디코드 벤치는 TwelveMonkeys, darkxanter, 사이드카 3자를 비교한다. 인코드 벤치는 darkxanter와 사이드카 2자다. TwelveMonkeys에는 WebP writer가 없다 [S24].

## 샘플 생성 중 추가로 확인한 사실 (2026-10-23, libwebp 1.6.0 도구 이미지)

samples/manifest.json의 `expected.observed`에 도구별 판정이 기록돼 있다. 위 "변조 생성 검증"(dwebp만 확인)을 보충한다.

- **VP8X 예약 플래그 비트**: dwebp는 받지만 WebPDemux(따라서 WebPAnimDecoder)는 거부한다. 24비트 예약 필드는 둘 다 받는다. 그래서 같은 파일이 정지 이미지 디코딩은 되고 `GetFeatures`(demux)는 실패할 수 있다.
- **ALPH 예약 비트, ALPH 압축 방식 2**: libwebp가 `VP8_STATUS_OUT_OF_MEMORY`로 거부한다(RFC 9649 §2.7.1.2는 예약 비트를 무시하라고 한다).
- **libwebp가 받는 손상 파일**: VP8 뒤의 ALPH, 이미지 뒤의 ICCP, 중복 VP8, 실제보다 100바이트 작은 VP8 청크 크기, 패딩 누락, 비트스트림과 맞지 않는 ANMF 크기, 중첩 ANMF.
- **중복 EXIF/ICCP**: WebPDemux는 첫 청크를 돌려주고, WebPMux는 파일을 거부한다.
- **출력량 폭탄**: 4096² 캔버스에 1×1 프레임 1,000개인 파일은 기본 상한(픽셀, 프레임 수)을 모두 통과하지만 합성 프레임 출력이 약 64 GiB다. 메모리는 스트리밍으로 제한되지만 CPU·전송량을 소모한다(Phase 5 검토 항목).
- **webpmux**: 홀수 오프셋을 짝수로 내림하고, 프레임 1개짜리 애니메이션은 정지 이미지로 바꾼다.

## 미확인 항목

- libwebp-test-data 데이터 파일의 라이선스(LICENSE 파일이 없다)와 `bryce.webp` 원본 사진의 출처.
- Kodak 이미지 라이선스의 1차 원문. r0k.us가 HTTP 401을 반환해 2차 출처만 확인했다.
- CLIC 데이터셋의 라이선스. DIV2K는 학술 연구 전용으로 확인했다.
- Google gallery1 1~3번과 gallery2 4번을 우리가 재배포할 권한.
- ICC `sRGB2014.icc`의 정확한 파일 크기와 다운로드 경로.
- RFC 9649 errata 유무. 검색 페이지에 필터가 적용됐는지 확인하지 못했다.
- libwebp main(`1844042`)에서 ALPH Rsv 오류가 보고되는 상태 코드. 1.5.0에서는 `OUT_OF_MEMORY`로 보고된다.
- image-rs/image-webp 저장소의 라이선스.
- EXIF 청크에 `Exif\0\0` 접두어를 붙이는 작성기가 실제로 있는지(추정만 했다).
- malformed WebP 전용 공개 코퍼스가 있는지(검색 범위에서 찾지 못했다).

## 출처

- [S1] webm/libwebp-test-data@06ddd96e276c2c638a72d39d3c0f340afd61978c (2025-08-26) — https://chromium.googlesource.com/webm/libwebp-test-data. GitHub 미러 https://github.com/webmproject/libwebp-test-data 의 HEAD도 같다. `libwebp_tests.md5`, `test_dwebp.sh`, `test_lossless.sh`, `lossless_vec_list.txt`, 커밋 `05cd58b`(PGM md5 갱신). scratchpad에 clone해 RIFF 구조를 스캔했고, 로컬 dwebp 1.5.0으로 md5를 다시 계산했다 (확인 2026-10-23)
- [S2] webm/libwebp@1844042d1ca751e62e61df7617dba19c98588339 (2026-10-19) — https://chromium.googlesource.com/webm/libwebp. `COPYING`, `PATENTS`, `NEWS`, `examples/`, `imageio/jpegdec.c`, `src/demux/anim_decode.c`, `src/dec/alpha_dec.c`, `src/webp/format_constants.h`, `src/utils/utils.h`. 태그: v1.5.0=`a4d7a715`, v1.6.0=`4fa21912`(2025-07-07), webp-rfc9649=`dfdcb7f9`. `0d14d84b`는 어느 태그에도 포함되지 않는다(`git tag --contains`) (확인 2026-10-23)
- [S3] libwebp@1844042 `tests/fuzzer/oss-fuzz/build.sh` — 시드 코퍼스를 만드는 방법과 `WEBP_MAX_IMAGE_SIZE` (확인 2026-10-23)
- [S4] OSS-Fuzz, Corpora — https://google.github.io/oss-fuzz/advanced-topics/corpora/ (확인 2026-10-23)
- [S5] libwebp@1844042 `man/cwebp.1`, `dwebp.1`, `img2webp.1`, `gif2webp.1`, `webpmux.1`. 로컬 Ubuntu `webp 1.5.0-0.1build1`에서 실행해 확인했다 (확인 2026-10-23)
- [S6] RFC 9649, WebP Image Format — https://www.rfc-editor.org/rfc/rfc9649 (.txt, .html) (확인 2026-10-23)
- [S7] RFC 9649 errata 검색 — https://errata.rfc-editor.org/search/?rfc_number=9649 (확인 2026-10-23)
- [S8] RFC 6386 §9.1 — https://www.rfc-editor.org/rfc/rfc6386 (확인 2026-10-23)
- [S9] PngSuite — http://www.schaik.com/pngsuite/ , `PngSuite.LICENSE`, `PngSuite-2017jul19.tgz`(68,521 B, sha256 `0294b244c95a8342c01b00010cf34abdcabc7c6a34fd0fe1bd963917537bfdc8`). scratchpad에서 풀어 청크를 검사했다 (확인 2026-10-23)
- [S10] Google WebP Gallery — https://developers.google.com/speed/webp/gallery1 (페이지 갱신 2025-08-07) (확인 2026-10-23)
- [S11] Google WebP Lossless and Alpha Gallery — https://developers.google.com/speed/webp/gallery2 (페이지 갱신 2025-08-07) (확인 2026-10-23)
- [S12] Kodak — http://r0k.us/graphics/kodak/ (HTTP 401). 2차 출처: https://aomedia.googlesource.com/libavif/+/c2a508f04ce49338faa2f4052b57ec674b2c22c0/tests/data/README.md (확인 2026-10-23)
- [S13] DIV2K — https://data.vision.ee.ethz.ch/cvl/DIV2K/ (확인 2026-10-23)
- [S14] CLIC — https://clic2027.compression.cc/ , https://www.tensorflow.org/datasets/catalog/clic (확인 2026-10-23)
- [S15] Wikimedia Commons:Licensing — https://commons.wikimedia.org/wiki/Commons:Licensing (확인 2026-10-23)
- [S16] ICC sRGB profiles — https://registry.color.org/rgb-registry/srgbprofiles (확인 2026-10-23)
- [S17] ICC profile library, 라이선스 조건 — https://registry.color.org/profile-library/ (확인 2026-10-23)
- [S18] saucecontrol/Compact-ICC-Profiles@bdd84663061bc4ae95ca70decff54f581e27f702 — https://github.com/saucecontrol/Compact-ICC-Profiles , `license`(CC0 1.0) (확인 2026-10-23)
- [S19] ianare/exif-samples@5332a9cf0220e9c6c93f88a187daa90808051e10 — https://github.com/ianare/exif-samples , `README.rst` (확인 2026-10-23)
- [S20] ExifTool Sample Images — https://exiftool.org/sample_images.html (확인 2026-10-23)
- [S21] mistymntncop/CVE-2023-4863@cb9f7aafa19b4ebe1b859eb12d2ba350dd482769 — https://github.com/mistymntncop/CVE-2023-4863 (LICENSE 없음) (확인 2026-10-23)
- [S22] Isosceles, "The WebP 0day" — https://blog.isosceles.com/the-webp-0day/ (확인 2026-10-23)
- [S23] libwebp 커밋 902bc9190331343b2017211debcec8d2ab87e17a (2023-09-07). v1.3.2 = `ca332209cb5567c9b249c86788cb2dbf8847e760`(2023-09-13), `NEWS` "security fix for lossless decoder (chromium: #1479274, CVE-2023-4863)" (확인 2026-10-23)
- [S24] haraldk/TwelveMonkeys@57750b651f278aac3dd970804123d7f72ee4c75c — `README.md` 55행(WebP 읽기 ✔, 쓰기 -), `LICENSE.txt`(BSD 3-Clause), https://github.com/haraldk/TwelveMonkeys/tree/master/imageio/imageio-webp/src/test/resources/webp (확인 2026-10-23)
- [S25] darkxanter/webp-imageio@aed48c59ce5cc2145ea17c548c755b8aae947709 — https://github.com/darkxanter/webp-imageio (Apache-2.0). 번들된 libwebp 버전은 docs/research/0001을 따른다 (확인 2026-10-23)
- [S26] image-rs/image-webp@f4d80bd965df2c81e65b6f43c1f70e0750bd4b0f — `tests/CREDITS.md` (확인 2026-10-23)
- [S27] google/wuffs `test/3pdata/fetch-webpsuite.sh` — https://skia.googlesource.com/external/github.com/google/wuffs/+/0ce35fefdeb17ad340132a816f51cc59dfbe613a/test/3pdata/fetch-webpsuite.sh (test-data를 커밋 `260804d6`으로 고정해 clone한다) (확인 2026-10-23)
