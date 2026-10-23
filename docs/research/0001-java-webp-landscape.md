# 0001. Java WebP 라이브러리 현황

- 상태: 초안
- 작성일/확인일: 2026-10-23
- 관련 ADR: ADR-0001

## 요약

- 공개된 Java ImageIO 플러그인 중 애니메이션(합성, duration, loop)과 ICC/EXIF/XMP를 모두 처리하는 것은 이번 조사에서 확인되지 않았다.
- TwelveMonkeys imageio-webp는 순수 Java 읽기 전용이다. 애니메이션은 ANMF 프레임을 하나씩 원시 상태로만 읽고, IIOMetadata는 표준 포맷(압축 방식, chroma)만 노출한다. EXIF/XMP 파싱 코드는 있으나 호출되지 않아 사용자에게 노출되지 않는다 [S2][S4][S7].
- libwebp JNI 계열(sejda, darkxanter, usefulness)은 `WebPGetFeatures`/`WebPDecode`/`WebPEncode` 단순 API만 호출한다. IIOMetadata는 항상 null이다. 코드 경로상 애니메이션 파일은 첫 프레임도 얻지 못하고 IOException으로 끝난다(실행 검증은 하지 않았다) [S9][S11][S14][S24].
- sejda 0.1.6(2020-05-27)은 libwebp v1.0.0에 1커밋을 더한 지점을 고정하고 있다. 따라서 CVE-2023-4863(libwebp < 1.3.2)과 CVE-2023-1999(0.4.2 이상 1.3.1 미만)의 영향 범위에 든다. darkxanter 0.3.3(2023-11-28)은 1.3.2를 번들한다. libwebp 최신 릴리스는 1.6.0이다 [S9][S11][S13][S15][S17].
- 사전 조사 표에 없는 활성 선택지가 있다. `com.github.usefulness:webp-imageio` 0.11.0은 JNI에 libwebp 1.6.0을 번들하지만 기능 범위는 sejda/darkxanter와 같다. demux/anim API를 쓰는 바인딩은 NightMonkeys(FFM, 읽기 전용), webp4j(JNI, ImageIO 아님), scrimage-webp(CLI 바이너리 호출) 정도다 [S24]–[S27].
- IDR Solutions가 공개한 벤치마크에서 darkxanter(JNI)의 읽기 처리량은 JDeli의 약 17.8배, TwelveMonkeys의 약 19.3배다. 측정 환경, 라이브러리 버전, 코퍼스가 공개되지 않은 벤더 수치다 [S18][S22].

## 사전 조사와 달라진 점

| # | 항목 | 사전 조사 | 검증 결과 | 근거 |
|---|---|---|---|---|
| 1 | TwelveMonkeys EXIF/XMP | 읽기 | **노출되지 않는다.** EXIF/XMP를 파싱하는 `readMeta()`는 private이고 호출처가 없다. 이 상태가 2020-11-22 추가 이후 유지되고 있다. | [S2] `WebPImageReader.java:705-769`, [S7] |
| 2 | TwelveMonkeys IIOMetadata | 제공 | 표준 포맷(`javax_imageio_1.0`)만 제공한다. 압축명(VP8/VP8L), 무손실 여부, chroma, pixel aspect만 들어 있다. native 포맷은 없고 EXIF/XMP/ICC/애니메이션 정보도 없다. | [S4] |
| 3 | TwelveMonkeys ICC | 읽기 후 적용 | 대체로 맞다. 다만 RGB 프로파일만 적용하고 비-RGB 프로파일은 경고를 낸 뒤 무시한다. 원본 프로파일은 메타데이터로 노출되지 않는다. | [S2] `:311-334`, `:635-653` |
| 4 | TwelveMonkeys 애니메이션 | 프레임 단위 읽기만 | 맞다. 추가로 다음이 확인됐다. 프레임은 캔버스가 아니라 ANMF 프레임 크기로 반환된다. x/y 오프셋은 노출되지 않는다. `getNumImages(false)`는 -1을 반환한다. ANIM의 loop/배경색 읽기 코드는 주석 처리돼 있다. | [S2] `:152-182`, `:361-398` |
| 5 | sejda libwebp | 1.0.0 (2018) | 고정 지점은 `v1.0.0-1-g4b282e1`(2018-05-11)로, 태그 자체가 아니다. 추가된 1커밋은 `examples/`만 바꾸므로 라이브러리 코드는 1.0.0과 같다. | [S9], [S13] |
| 6 | JNI 계열 애니메이션 | ❌ | 기능이 없을 뿐 아니라 첫 프레임조차 얻지 못하고 예외가 난다. libwebp `WebPDecode`가 애니메이션 비트스트림에 `VP8_STATUS_UNSUPPORTED_FEATURE`를 반환하고, Java 측이 이를 IOException으로 바꾼다. 코드 분석 결과이며 실행 검증은 하지 않았다. | [S14], [S9] `WebP.java:63-71` |
| 7 | darkxanter libwebp | 1.3.2 (2023) | 1.3.2는 0.3.3에서만 번들된다. 0.3.0~0.3.2는 libwebp 1.3.0을 번들하므로 두 CVE의 영향 범위에 든다. groupId는 `io.github.darkxanter`다. | [S11] `cdd528b`, `93fd9d9`, [S12] |
| 8 | 네이티브 플랫폼 | (언급 없음) | sejda는 linux-x86_64, mac-x86_64, win-x86/x64용만 번들한다. Linux aarch64와 Apple Silicon용은 없다. darkxanter는 Linux aarch64/arm/ppc64와 Mac aarch64를 포함한다. | [S9], [S11] |
| 9 | 벤치마크 배수 | ~18x | JDeli 대비 17.8배(16.933/0.950), TwelveMonkeys 대비 19.3배(16.933/0.877)다. 벤치마크 저장소 pom에는 TwelveMonkeys 3.9.4(2022-11)와 darkxanter 0.3.1(2023-03, libwebp 1.3.0)이 주석 처리된 상태로 남아 있다. 측정이 이 구버전 기준이었을 가능성이 있다(추정). | [S18], [S22] |
| 10 | 벤더 비교표 | (출처 없음) | IDR 비교표의 일부 항목이 소스와 다르다. darkxanter의 "Animation read/write: Yes"와 TwelveMonkeys의 "EXIF read: Yes"는 소스 분석 결과와 맞지 않는다. IDR이 주장하는 CVSS "10.0"도 NVD 기준 8.8과 다르다. | [S18], [S2], [S11], [S15] |
| 11 | 비교 대상 | 4종 | 사전 조사 표에 없는 활성 선택지가 있다(usefulness 포크, NightMonkeys, webp4j, scrimage-webp). "기타 선택지" 절 참조. | [S24]–[S27] |

## 비교표 (검증 결과)

| 항목 | TwelveMonkeys imageio-webp | sejda webp-imageio | darkxanter webp-imageio | JDeli (상용) |
|---|---|---|---|---|
| 방식 | 순수 Java, ImageIO Reader만 [S5] | JNI + 번들 libwebp v1.0.0+1 (2018-05) [S9][S13] | JNI + 번들 libwebp v1.3.2 (2023-09) [S11][S13] | 순수 Java(벤더 주장) [S18] |
| Maven 최신 | `com.twelvemonkeys.imageio:imageio-webp` 3.15.3 (2026-10-06) [S8] | `org.sejda.imageio:webp-imageio` 0.1.6 (2020-05-27) [S10] | `io.github.darkxanter:webp-imageio` 0.3.3 (2023-11-28) [S12] | 미확인 |
| 읽기/쓰기 | 읽기만 [S5][S6] | 읽기/쓰기 [S9] | 읽기/쓰기 [S11] | 읽기/쓰기 [S18] |
| lossy/lossless/alpha | 읽기. 비압축 ALPH는 미구현으로, 경고 후 불투명 처리된다 [S2] | 읽기/쓰기 [S9] | 읽기/쓰기 [S11] | 읽기/쓰기 [S18][S19][S20] |
| 애니메이션 | 프레임 단위 원시 읽기만. blend/dispose 합성, duration, loop, 오프셋 모두 미노출 [S2][S3] | ❌ (디코드 시 예외, 코드 분석) [S14] | ❌ (동일) | ❌ (문서에 명시) [S18][S19][S20] |
| ICC | RGB 프로파일만 적용, 비-RGB는 무시, 메타데이터 미노출 [S2] | ❌ (처리 코드 없음) [S9] | ❌ [S11] | 문서에 없음 [S18][S19][S21] |
| EXIF / XMP | ❌ (파싱 코드는 있으나 호출되지 않음) [S2][S7] | ❌ [S9] | ❌ [S11] | EXIF 읽기/쓰기. XMP는 문서에 없음 [S18][S21] |
| IIOMetadata | 표준 포맷만(압축, chroma) [S4] | null [S9] | null [S11] | 미확인. 자체 API `WebpMetadata.getExif()`가 있다 [S21] |
| 번들 네이티브 | 불필요 | linux-x64, mac-x64, win-x86/x64 [S9] | Linux x86/x64/aarch64/arm/armv6/armv7/ppc64, Mac x64/aarch64, Win x86/x64 [S11] | 불필요(벤더 주장) |
| CVE-2023-4863 | 해당 없음(libwebp 미사용) | 영향 범위(< 1.3.2) [S15] | 0.3.3은 수정판 포함. 0.3.0~0.3.2는 영향 범위 [S11][S15] | 해당 없음(벤더 주장) [S18] |
| 유지보수 | 활발. webp 모듈 최근 커밋 2026-10-09 [S1] | master 최종 커밋 2020-05-27 [S9] | 최종 커밋 2023-11-28 [S11] | 상용 |

## 라이브러리별 근거

### TwelveMonkeys imageio-webp

- 저장소 HEAD는 `57750b65`(2026-10-22)이고, `imageio/imageio-webp` 최종 커밋은 `d9da0333`(2026-10-09, HuffmanTable 입력 검증)이다. 2026년에도 VP8/VP8L 버그 수정과 입력 길이 검증 강화 커밋이 이어지고 있다 [S1].
- 쓰기를 지원하지 않는다. `META-INF/services`에는 `ImageReaderSpi`만 등록돼 있고, `WebPProviderInfo.java:48-53`의 writer 항목은 null이다 [S5]. README 표도 R ✔, W -, Metadata "Standard"로 적고 있다 [S6].
- 포맷 처리: VP8(lossy), VP8L(lossless), VP8X+ALPH를 디코드한다(`WebPImageReader.java:227-340`, `:502-521`). 비압축 알파(compression=0)는 "not implemented" 경고 후 불투명으로 채운다(`:667-671`) [S2].
- 애니메이션 [S2][S3]
  - `readFrame()`은 ANMF에서 x/y/w/h, duration, flags를 읽어 `AnimationFrame`에 담는다(`:167-182`). "TODO: Expose duration/flags in image metadata" 주석이 남아 있다(`:176`).
  - ANIM 청크의 배경색과 loop count 읽기 코드는 주석 처리돼 있다(`:152-165`).
  - `AnimationFrame.java:12-13,21-22`에 `blend`/`dispose` 필드가 있지만 리더 어디에서도 참조하지 않는다. 합성을 하지 않는다는 뜻이다.
  - `getWidth`/`getHeight`/`read`는 프레임 bounds 크기로 동작한다(`:389-398`, `:456-459`). 캔버스 합성 결과가 아니다.
  - `getNumImages(false)`는 애니메이션이면 -1을 반환한다(`:375`).
  - 위 TODO 주석은 2021-10-15 커밋 `74927d53`에서 들어온 뒤 바뀌지 않았다 [S7].
- ICC [S2]
  - VP8X의 ICC 플래그가 있으면 ICCP 청크를 읽는다. `ColorSpace.TYPE_RGB`일 때만 변환용 프로파일로 채택하고, 아니면 경고 후 무시한다(`:311-334`).
  - 디코드 뒤 `ColorConvertOp`로 목적지 색공간으로 변환한다(`:472`, `:635-653`).
  - 원본 프로파일 필드 `containedICCP`는 대입 외에 쓰이는 곳이 없다.
- EXIF/XMP: `readMeta()`(`:705-769`)는 `TIFFReader`/`XMPReader`로 파싱만 하고 결과를 지역 변수에 둔다. 이 메서드는 private이고 저장소 내에 호출처가 없다(grep 확인). 2020-11-22 커밋 `2376d16f`에서 추가된 뒤 호출 없이 남아 있다 [S2][S7].
- IIOMetadata: `getImageMetadata()`는 `WebPImageMetadata`를 반환한다(`:700-703`). 이 클래스는 `StandardImageMetadataSupport`에 압축명, lossless 여부, pixel aspect 1.0, format version "1.0"만 넣는다. "TODO: Get useful text nodes from EXIF or XMP" 주석이 있다(`WebPImageMetadata.java:9-18`). 테스트는 native 포맷 이름이 null임을 단언한다(`WebPImageMetadataTest.java:40`) [S4].

### sejda webp-imageio

- master HEAD는 `4aa8a892`(2020-05-27, Travis 설정)다. 그 이후 커밋은 미병합 dependabot 브랜치(2020-10-13) 하나뿐이다. GitHub상 archived는 아니고, open issue+PR은 30건이다(2026-10-23 API 조회) [S9][S23].
- libwebp
  - `.gitmodules`는 `https://chromium.googlesource.com/webm/libwebp`를 가리키고, gitlink는 `4b282e13`이다. `git describe` 결과는 `v1.0.0-1-g4b282e1`(2018-05-11 "gif2webp: fix transcode of loop count=65535")이며, 이 커밋은 `examples/`만 바꾼다 [S9][S13].
  - 번들 바이너리 `src/main/resources/native/{linux/64,mac/64,win/32,win/64}`는 2019-05-24~2020-05-27에 커밋됐다. 이 바이너리가 위 서브모듈로 빌드됐다는 것은 추정이다(바이너리를 분석하지 않았다).
- C 코드(`src/main/c/webp-imageio.c`) [S9]
  - include하는 헤더는 `webp/decode.h`, `webp/encode.h`뿐이다(`:20-21`).
  - 호출하는 함수는 `WebPGetInfo`(`:119`), `WebPGetFeatures`(`:169`), `WebPDecode`(`:208`), `WebPEncode`(`:307`)다.
  - `src/main/c/CMakeLists.txt:13`은 `webp` 타깃만 링크한다. demux/mux를 쓰지 않는다.
- Java 코드 [S9]
  - `WebPReader.java:55-57`의 `getNumImages()`는 항상 1을 반환하고, `:129-136`의 `getStreamMetadata`/`getImageMetadata`는 null을 반환한다.
  - `WebPWriter.java:42-58`의 메타데이터 관련 메서드는 모두 null을 반환한다.
  - 쓰기는 ColorModel에 알파가 있으면 `encodeRGBA`, 없으면 `encodeRGB`를 호출한다(`:94-101`). 압축 타입은 Lossy/Lossless 두 가지다(`WebPWriteParam.java`).
- 애니메이션: `WebPDecode` → `DecodeInto` → `WebPParseHeaders` 경로에서 애니메이션 플래그가 있으면 `VP8_STATUS_UNSUPPORTED_FEATURE`가 반환된다(libwebp v1.0.0과 v1.3.2 모두 `src/dec/webp_dec.c:414-432,456`). Java 측 `WebP.java:63-71`은 OK가 아닌 상태를 `IOException("Decode returned code ...")`로 던진다 [S14][S9].

### darkxanter webp-imageio

- HEAD는 `aed48c59`(2023-11-28, 0.3.3 릴리스 직후 개발 버전 전환)다. GitHub상 `viakunin/webp-imageio`의 포크다 [S11][S23].
- libwebp [S11][S13]
  - gitlink `ca332209`는 libwebp `v1.3.2^{}` 태그 커밋과 일치한다.
  - 커밋 `cdd528b`(2023-11-28 "update libwebp to 1.3.2")에서 서브모듈과 네이티브 바이너리 11종이 함께 갱신됐다.
  - 그 이전 `93fd9d9`(2023-03-27 "update to libwebp 1.3.0")는 0.3.0~0.3.2에 해당한다.
- 코드는 sejda와 같은 구조다. C 코드 차이는 `use_sharp_yuv` 속성 한 줄(`webp-imageio.c:377`)이고, Java에는 `WebPWriteParam`의 `setUseSharpYUV`가 추가됐다. `WebPGetFeatures`/`WebPDecode`/`WebPEncode` 호출 줄 번호와 `getNumImages()=1`, 메타데이터 null 처리는 sejda와 같다 [S11].
- 번들 바이너리 경로는 `src/main/resources/native/{Linux/{x86,x86_64,aarch64,arm,armv6,armv7,ppc64},Mac/{x86_64,aarch64},Windows/{x86,x86_64}}`다 [S11].

### JDeli (IDR Solutions, 상용)

- 제품 페이지의 주장 [S18]
  - "pure-Java", 읽기/쓰기, lossy(품질 설정)/lossless 인코딩, 알파 읽기/쓰기를 지원한다.
  - "JDeli reads and writes EXIF metadata embedded in WebP files"라고 적혀 있다.
  - "JDeli does not currently support reading or writing animated WebP files"라고 적혀 있다.
  - ICC와 XMP는 언급이 없다.
- Reader 문서 표: Animation No, Alpha Yes, Lossy Yes, Lossless Yes, Metadata EXIF [S19]. Writer 문서 표: Animation No, Metadata EXIF [S20].
- 메타데이터 문서는 WebP에 대해 `WebpMetadata.getExif()`만 안내하고, XMP와 ICC는 언급하지 않는다 [S21].
- 소스가 비공개라 위 내용은 모두 벤더 문서 기준이며 코드로 검증하지 못했다.

### CVE-2023-4863

- NVD [S15]
  - 게시일은 2023-09-12, CVSS 3.1 점수는 8.8(`AV:N/AC:L/PR:N/UI:R/S:U/C:H/I:H/A:H`), 분류는 CWE-787이다.
  - CPE는 `webmproject:libwebp`, `versionEndExcluding 1.3.2`이므로 1.3.2 미만이 영향 범위다.
  - CISA KEV에 2023-09-13 등재됐다.
  - 설명문에는 "libwebp 1.3.2"라는 모호한 표현이 있으나, 영향 범위는 CPE 기준으로 해석한다.
- libwebp NEWS@v1.3.2에는 "9/13/2023: version 1.3.2 … security fix for lossless decoder (chromium: #1479274, CVE-2023-4863)"라고 적혀 있다. v1.3.1..v1.3.2 사이의 `2af26267` "Fix OOB write in BuildHuffmanTable."이 수정 커밋이다(main의 `902bc919`를 체리픽한 것) [S13][S14].
- CVE-2023-5129는 "Duplicate of CVE-2023-4863"로 거부됐다 [S16]. IDR 페이지의 "maximum CVSS score of 10.0"은 NVD에서 확인되지 않는다. 거부된 중복 CVE의 초기 점수를 가리킨 것으로 추정한다 [S18].
- 함께 볼 CVE: CVE-2023-1999(lossless 인코더 UAF/double free, NVD 7.5)의 영향 범위는 libwebp 0.4.2 이상 1.3.1 미만이다 [S17]. sejda(1.0.0+1)와 darkxanter 0.3.0~0.3.2(1.3.0)가 이 범위에 든다.

## 기타 선택지

| 이름 | 방식 | 최신/유지보수 | 애니메이션 | 메타데이터 | 근거 |
|---|---|---|---|---|---|
| `com.github.usefulness:webp-imageio` | JNI + 번들 libwebp **v1.6.0**, ImageIO SPI(읽기/쓰기) | 0.11.0 (2026-06-15). 최근 커밋 2026-10-21. `gotson/webp-imageio`의 포크 | ❌ (`getNumImages()=1`, `WebPDecode`만 사용) | IIOMetadata null | [S24] |
| NightMonkeys `com.github.gotson.nightmonkeys:imageio-webp` | Panama FFM(JDK 22+), jextract 바인딩. 시스템 `libwebp`/`libwebpdemux` ≥1.2.4가 필요하며 번들하지 않는다. 읽기 전용 | 1.1.0 (2025-08-01). README에 "maintenance mode" 공지(2026-10-05) | `WebPAnimDecoder`로 프레임을 읽는다. duration/loop는 노출하지 않는다(`getImageMetadata` null) | ICC 적용. EXIF/XMP 미지원(플러그인 README 명시) | [S26] |
| webp4j `dev.matrixlab.webp4j:webp4j-core` | JNI + libwebp v1.6.0-34. 자체 API이며 ImageIO SPI는 없다 | 2.5.0 (메타데이터 갱신 2026-06-23) | `WebPAnimDecoder`/`WebPAnimEncoder` 사용. 프레임 timestamp와 loopCount 제공 | ICC/EXIF/XMP 처리 코드 없음(grep) | [S27] |
| scrimage-webp `com.sksamuel.scrimage:scrimage-webp` | 번들 libwebp **1.4.0** CLI(`cwebp`, `dwebp`, `gif2webp`, `img2webp`, `webpmux`, `anim_dump`)를 `ProcessBuilder`로 실행. scrimage 자체 리더 SPI이며 javax.imageio가 아니다 | 4.6.9 (2026-10-16) | `anim_dump`로 합성 프레임을 얻고 `webpmux -info`로 duration/loop를 얻는다(v4.5.0부터) | 미확인 | [S25] |
| WebPDecoderJN (tduva) | JNA + 자체 C 래퍼(`libwebp_animdecoder.c`), 디코드 전용 | 최종 커밋 2023-09-25 | 프레임 단위 디코드(README L3-4: "Decode static or animated WebP images into individual frames (and metadata)") | 미확인 | [S28] |
| Apache Commons Imaging | 순수 Java | 1.0.0-alpha6 | - | ICCP/EXIF/XMP **메타데이터만** 읽는다. 픽셀 디코드는 "Reading WebP files is currently not supported" | [S29] |
| libwebp SWIG Java 바인딩 | libwebp 저장소 `swig/`(`libwebp_java_wrap.c`) | libwebp와 함께 배포된다 | ❌ (`WebPDecodeRGB*` 등 단순 API만) | ❌ | [S30] |

## 벤치마크 수치 (벤더 공개값)

출처는 IDR Solutions(JDeli 판매사)가 게시한 페이지 https://www.idrsolutions.com/jdeli/webp 다 [S18]. 페이지에 적힌 측정 조건은 "Throughput mode, 25 measurement iterations, units in operations per second"뿐이다.

| 읽기 | ops/s | 오차 |
|---|---|---|
| JDeli | 0.950 | ± 0.073 |
| TwelveMonkeys | 0.877 | ± 0.107 |
| darkxanter/webp-imageio | 16.933 | ± 0.185 |

| 쓰기 | ops/s | 오차 | 읽을 수 없는 출력 |
|---|---|---|---|
| JDeli lossy | 3.659 | ± 0.034 | 0 |
| JDeli lossless | 4.632 | ± 0.049 | 2 |
| darkxanter lossy | 6.058 | ± 0.141 | 28 |
| darkxanter lossless | 1.067 | ± 0.028 | 28 |

- 배수: darkxanter의 읽기 처리량은 JDeli의 17.8배, TwelveMonkeys의 19.3배다(위 값으로 계산).
- Writer 문서에는 별도 측정값이 있다(darkxanter lossy 6.374, lossless 1.141, JDeli lossy 3.640, lossless 4.477, 파일 166개, "2020 13inch M1 MacBook Pro using JDK 18.0.1.1"). 제품 페이지 값과 조금 다르다 [S20].
- 제품 페이지의 읽기 표는 하드웨어, JDK, 코퍼스, 측정일을 밝히지 않는다 [S18].
- 공개 JMH 저장소 `idrsolutions/jmh-jdeli`에는 WebP 테스트 이미지가 없다. `testImages/images-for-read-tests/webp/readme.txt`에 외부 샘플 사이트 링크만 있다 [S22].
- 같은 저장소의 `pom.xml:85-92`에는 TwelveMonkeys 3.9.4와 darkxanter 0.3.1 의존성이 주석 처리돼 있다. 측정 버전으로 추정하지만 확정할 수 없다 [S22].
- `ReadTest.java:20-34`는 디코드 예외를 잡아 출력만 하고 루프를 계속한다. 코퍼스에 애니메이션 WebP가 섞여 있었다면 JNI 쪽은 실패가 빨리 끝나 처리량이 부풀려졌을 수 있다(추정) [S22][S14].

## 미확인 항목

- sejda/darkxanter 번들 바이너리 안의 실제 libwebp 버전. 서브모듈 포인터와 커밋 이력으로만 판단했고, 바이너리는 실행하거나 분석하지 않았다.
- JNI 계열이 애니메이션 WebP에서 IOException을 던진다는 점은 코드 경로 분석 결과다. 실제 실행으로 확인하지 않았다.
- JDeli: ICC 처리, XMP, ImageIO 플러그인 경로의 IIOMetadata 내용, 배포 형태와 최신 버전, "pure Java" 주장의 내부 구현(소스 비공개).
- IDR 읽기 벤치마크의 하드웨어, JDK, 코퍼스, 측정일, 사용한 라이브러리 버전.
- scrimage-webp와 WebPDecoderJN의 메타데이터(ICC/EXIF/XMP) 처리 여부. WebPDecoderJN의 Maven 배포 여부.
- 그 밖의 FFM 기반 libwebp 바인딩. 웹 검색 범위에서는 NightMonkeys 외에 찾지 못했다.
- GitHub 저장소 통계 일부(포크 계보 상위, 일부 저장소 상태). 조회 중 API 속도 제한에 걸려 확인하지 못했다.

## 출처

- [S1] haraldk/TwelveMonkeys@57750b651f278aac3dd970804123d7f72ee4c75c — `git log -- imageio/imageio-webp` (최종 d9da033312af7461b4a99cbafe2a79f1d6d66e7b, 2026-10-09; 태그 twelvemonkeys-3.15.3 = ed21cf69, 2026-10-06) (확인 2026-10-23)
- [S2] haraldk/TwelveMonkeys@57750b651f27:imageio/imageio-webp/src/main/java/com/twelvemonkeys/imageio/plugins/webp/WebPImageReader.java — L152-182, L227-340, L311-334, L361-398, L456-472, L502-541, L635-653, L667-671, L700-769 (확인 2026-10-23)
- [S3] haraldk/TwelveMonkeys@57750b651f27:imageio/imageio-webp/src/main/java/com/twelvemonkeys/imageio/plugins/webp/AnimationFrame.java — L12-13, L21-22 (확인 2026-10-23)
- [S4] haraldk/TwelveMonkeys@57750b651f27:imageio/imageio-webp/src/main/java/com/twelvemonkeys/imageio/plugins/webp/WebPImageMetadata.java — L9-18; `src/test/java/.../WebPImageMetadataTest.java` L40 (확인 2026-10-23)
- [S5] haraldk/TwelveMonkeys@57750b651f27:imageio/imageio-webp/src/main/java/com/twelvemonkeys/imageio/plugins/webp/WebPProviderInfo.java — L48-53; `src/main/resources/META-INF/services/javax.imageio.spi.ImageReaderSpi` (확인 2026-10-23)
- [S6] haraldk/TwelveMonkeys@57750b651f27:README.md — L26-27, L55 (확인 2026-10-23)
- [S7] `git blame` WebPImageReader.java@57750b65 — L152-178: 74927d53 (2021-10-15), L177: b856ce07 (2022-08-29), L700-706: 2376d16f (2020-11-22) (확인 2026-10-23)
- [S8] https://repo1.maven.org/maven2/com/twelvemonkeys/imageio/imageio-webp/maven-metadata.xml — release 3.15.3, `imageio-webp-3.15.3.pom` Last-Modified 2026-10-06 (확인 2026-10-23)
- [S9] sejda-pdf/webp-imageio@4aa8a89217ba568adb7a0eb6cd6238450417c5a0 — `.gitmodules`; gitlink `libwebp` = 4b282e13ad0cf7f1a3b4431788bddef1d6b11762; `src/main/c/webp-imageio.c` L20-21, L119, L169, L208, L307; `src/main/c/CMakeLists.txt` L13; `src/javase/java/com/luciad/imageio/webp/WebPReader.java` L55-57, L129-136; `WebPWriter.java` L42-58, L94-101; `src/main/java/com/luciad/imageio/webp/WebP.java` L63-71; `src/main/resources/native/` (`file` 명령으로 아키텍처 확인) (확인 2026-10-23)
- [S10] https://repo1.maven.org/maven2/org/sejda/imageio/webp-imageio/maven-metadata.xml — release 0.1.6, `webp-imageio-0.1.6.pom` Last-Modified 2020-05-27 (확인 2026-10-23)
- [S11] darkxanter/webp-imageio@aed48c59ce5cc2145ea17c548c755b8aae947709 — gitlink `libwebp` = ca332209cb5567c9b249c86788cb2dbf8847e760; 커밋 cdd528b15b84 (2023-11-28), 93fd9d9 (2023-03-27); `src/main/c/webp-imageio.c` L169, L208, L307, L377; `src/javase/.../WebPReader.java` L55-57, L129-136; `src/main/resources/native/` (확인 2026-10-23)
- [S12] https://repo1.maven.org/maven2/io/github/darkxanter/webp-imageio/maven-metadata.xml — release 0.3.3 (POM Last-Modified 2023-11-28), 0.3.1 POM Last-Modified 2023-03-28 (확인 2026-10-23)
- [S13] https://chromium.googlesource.com/webm/libwebp — `git ls-remote --tags` (v1.0.0^{}=698b8844, v1.3.2^{}=ca332209, v1.6.0^{}=4fa21912); `git describe 4b282e13` = v1.0.0-1-g4b282e1 (변경 파일 examples/ 4개); `NEWS`@v1.3.2, `NEWS`@v1.6.0 (확인 2026-10-23)
- [S14] libwebp `src/dec/webp_dec.c`@v1.3.2 L414-432, L447-456 (@v1.0.0 동일 위치); 커밋 2af26267cdfcb63a88e5c74a85927a12d6ca1d76 (v1.3.2 브랜치), 902bc9190331343b2017211debcec8d2ab87e17a (main) (확인 2026-10-23)
- [S15] https://nvd.nist.gov/vuln/detail/CVE-2023-4863 (API: https://services.nvd.nist.gov/rest/json/cves/2.0?cveId=CVE-2023-4863) (확인 2026-10-23)
- [S16] https://nvd.nist.gov/vuln/detail/CVE-2023-5129 — Rejected, "Duplicate of CVE-2023-4863" (확인 2026-10-23)
- [S17] https://nvd.nist.gov/vuln/detail/CVE-2023-1999 — libwebp 0.4.2 이상 1.3.1 미만, NVD 7.5 (확인 2026-10-23)
- [S18] https://www.idrsolutions.com/jdeli/webp — IDR Solutions 제품 페이지(벤더) (확인 2026-10-23)
- [S19] https://www.idrsolutions.com/docs/jdeli/tutorials/reading/java-webp-reader (확인 2026-10-23)
- [S20] https://www.idrsolutions.com/docs/jdeli/tutorials/writing/java-webp-writer (확인 2026-10-23)
- [S21] https://www.idrsolutions.com/docs/jdeli/tutorials/how-to-read-image-metadata (확인 2026-10-23)
- [S22] idrsolutions/jmh-jdeli@4f1725fb9b35059a6edaacf2af9fc36bab739bfb — `pom.xml` L85-92; `src/main/java/read/ReadTest.java` L20-34; `src/main/java/read/WEBP.java`; `testImages/images-for-read-tests/webp/readme.txt`; pom 의존성 추가 커밋 53f0d5e (2024-01-18) (확인 2026-10-23)
- [S23] GitHub REST API `GET /repos/{sejda-pdf,darkxanter,usefulness}/webp-imageio`, `/repos/haraldk/TwelveMonkeys`, `/repos/sksamuel/scrimage` — archived, pushed_at, open_issues_count, parent (확인 2026-10-23)
- [S24] usefulness/webp-imageio@1c46e860b2934811561bae816999174620f7bba4 — gitlink `libwebp-jni/libwebp` = 4fa21912 (v1.6.0); 커밋 4549a76 (2026-06-15)이 태그 v0.11.0에 포함; `libwebp-jni/src/main/c/webp-imageio.c` L208, L310; `libwebp-jni/src/main/c/CMakeLists.txt` L13; `webp-imageio/src/main/kotlin/com/luciad/imageio/webp/internal/WebPReader.kt` L47, L110-112; https://repo1.maven.org/maven2/com/github/usefulness/webp-imageio/maven-metadata.xml (0.11.0, POM Last-Modified 2026-06-15) (확인 2026-10-23)
- [S25] sksamuel/scrimage@994cc47b8b695a9a4b89083e033958e58801aa81 — `scrimage-webp/src/main/resources/dist_webp_binaries/libwebp-1.4.0-*`; `AnimatedWebpReader.java` L17-67; `WebpMuxHandler.java`; `CWebpHandler.java` L114; `META-INF/services/com.sksamuel.scrimage.nio.ImageReader`; 커밋 2d86f67b (2026-05-01)를 처음 포함한 태그 v4.5.0 (2026-05-01); https://repo1.maven.org/maven2/com/sksamuel/scrimage/scrimage-webp/maven-metadata.xml (4.6.9, POM Last-Modified 2026-10-16) (확인 2026-10-23)
- [S26] gotson/NightMonkeys@23200cf14e0b10417120f61f042334a732bd3060 — `README.md` L6-29; `imageio-webp/README.md`; `imageio-webp/src/main/java22/com/github/gotson/nightmonkeys/webp/WebP.java` L217-218; `.../imageio/plugins/WebpImageReader.java` L150-155; https://repo1.maven.org/maven2/com/github/gotson/nightmonkeys/imageio-webp/maven-metadata.xml (1.1.0, POM Last-Modified 2025-08-01) (확인 2026-10-23)
- [S27] MrNanko/webp4j@313951670f0fac389b6abc7b1a1d10f95daea60f — gitlink `libwebp` = 4bcea048 (v1.6.0-34-g4bcea04); `src/main/c/webp4j.c` (WebPAnimDecoder*/WebPAnimEncoder*); `src/main/java/dev/matrixlab/webp4j/model/AnimationInfo.java` L18, `AnimatedWebPFrame.java` L14; https://repo1.maven.org/maven2/dev/matrixlab/webp4j/webp4j-core/maven-metadata.xml (2.5.0, lastUpdated 2026-06-23) (확인 2026-10-23)
- [S28] tduva/WebPDecoderJN@a29e4e295612a5833cfde96820fc42c5cdd602e5 (2023-09-25) — `README.md` L3-4; `lib/build.gradle` L15 (JNA 5.12.1); `build-native/*/libwebp_animdecoder.c` (확인 2026-10-23)
- [S29] apache/commons-imaging@4ee55f32c6a8c35504a70ee708d6de6c34a1ae41 — `src/main/java/org/apache/commons/imaging/formats/webp/WebPImageParser.java` L201-203, L217, L317, L330; https://repo1.maven.org/maven2/org/apache/commons/commons-imaging/maven-metadata.xml (1.0.0-alpha6) (확인 2026-10-23)
- [S30] libwebp `swig/libwebp.swig`@v1.6.0 — L101-137 (확인 2026-10-23)
