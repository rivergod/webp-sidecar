# 0006. 테스트 샘플: 자체 생성 세트를 커밋하고, 외부 세트는 고정 커밋으로 받는다

- 상태: accepted
- 날짜: 2026-10-23
- 결정자: Ilkang Na
- 관련 문서: [docs/research/0005-test-samples.md](../research/0005-test-samples.md), [docs/conventions/testing.md](../conventions/testing.md), [ADR-0004](0004-pixel-transfer-format.md)

## 맥락

단위·통합·적합성 테스트와 벤치마크에 쓸 WebP 샘플이 필요하다. 커버리지: lossy, lossless, alpha, 애니메이션(blend/dispose 조합), ICC, EXIF, XMP, 손상/악성 입력, 크기별 벤치 이미지.

조사 결과([research/0005](../research/0005-test-samples.md), 확인 2026-10-23):

- **libwebp-test-data**(커밋 `06ddd96e276c`, 23.5MB): 정지 이미지 131개(lossy 70, lossless 43, lossy+alpha 18). **애니메이션, ICCP, EXIF, XMP 파일은 없다.** `dwebp` 디코딩 결과의 md5 목록(`libwebp_tests.md5`)으로 적합성을 확인한다. LICENSE 파일이 없고 스크립트에 "libwebp와 같은 조건"이라고만 적혀 있다(BSD-3-Clause 추정, 미확인).
- 로컬 libwebp 도구(`cwebp`, `img2webp`, `webpmux`, `gif2webp`, `anim_dump` 등 1.5.0)로 필요한 커버리지를 모두 만들 수 있음을 실제로 확인했다. blend/dispose를 명시적으로 제어하는 것은 `webpmux -frame`뿐이다.
- 손상 WebP 공개 코퍼스는 찾지 못했다. CVE-2023-4863 PoC 저장소는 라이선스가 없다. 직접 변조한 14개 파일은 모두 `dwebp`가 거부했다.
- 외부 이미지 라이선스: PngSuite는 자유 사용·배포 허가, Compact-ICC-Profiles는 CC0, DIV2K는 학술 연구 전용, Kodak은 1차 출처 미확인, Google WebP 갤러리는 이미지마다 다르다.
- libwebp는 애니메이션 합성에서 ANIM 배경색을 무시하고 투명으로 초기화한다. RFC 9649 §2.7.1.2는 ALPH 예약 비트를 무시하라고 하지만 libwebp는 그런 파일을 거부한다.

## 결정 동인

1. **재배포 가능한 라이선스** — 저장소에 들어가는 파일은 재배포가 명시적으로 허용돼야 한다.
2. **커버리지** — 애니메이션과 메타데이터까지 다뤄야 한다(공식 테스트 벡터에는 없다).
3. **재현성** — 같은 입력과 기대값이 어디서나 같아야 한다(해시 고정).
4. **저장소 크기** — clone이 가벼워야 한다.

## 검토한 선택지

1. **자체 생성 세트 커밋 + 외부 세트는 고정 커밋으로 빌드 시 받기**
2. 외부 세트(libwebp-test-data 등)를 저장소에 복사
3. 자체 생성만 사용(외부 세트 없음)
4. 샘플을 커밋하지 않고 테스트 실행 시 모두 생성

## 결정

**선택지 1을 택한다.**

```
samples/
  manifest.json      기계용 단일 원본: path, sha256, size, origin, license, tags, generator, expected, rfc
  SOURCES.md         manifest.json에서 생성하는 사람용 표
  generated/         커밋. 자체 생성(inputs, still, anim, meta, expected)
  malformed/         커밋. generated/를 시드로 변조 스크립트가 만든다
  third_party/       선택. 재배포 허용이 명시된 것만(PngSuite + 라이선스 문구, CC0 ICC 프로파일)
  conformance/       커밋하지 않음. libwebp-test-data를 커밋 06ddd96e로 받고 sha256 검증
  bench/             커밋하지 않음. 벤치 전에 생성하거나 받는다
```

- **커밋 기준**: 파일당 64 KiB 이하, `samples/` 합계 5 MiB 이하, 라이선스가 자체 생성·CC0·명시적 재배포 허가 중 하나. 숫자는 조정 가능하다.
- **생성·변조·내려받기 스크립트**는 `samples/scripts/`에 둔다. 생성에 쓴 도구와 버전을 manifest의 `generator`에 기록한다. 이 CLI 사용은 테스트 데이터 생성용이며 서버 구현과 무관하다.
- **받는 데이터**는 URL·커밋·파일별 sha256을 manifest에 고정하고, 불일치하면 실패시킨다. 캐시는 저장소 밖에 둔다.
- **기대값**
  - 파일 무결성: SHA-256.
  - 디코딩 결과: ADR-0004 와이어 포맷(RGBA8, 비-premultiplied, stride = w × 4) 원시 바이트의 SHA-256과 w, h.
  - libwebp-test-data: 결과로 `dwebp`와 같은 PAM 바이트를 만들어 `libwebp_tests.md5`의 `.pam` 줄과 비교한다(`.pgm` 줄은 미발표 libwebp 변경으로 갱신돼 있어 쓰지 않는다).
  - 애니메이션 합성 기대값은 **libwebp 동작**(`anim_dump`/`WebPAnimDecoder`)을 기준으로 한다. RFC와 libwebp가 다른 지점(배경색, ALPH 예약 비트)은 테스트 주석에 적는다.
- **쓰지 않는 것**: DIV2K(학술 전용), CVE PoC 저장소(라이선스 없음), 라이선스를 확인할 수 없는 이미지.
- **벤치 사진 소스**는 Phase 4 전에 정한다. 후보는 public domain 또는 CC0로 명시된 개별 파일(Wikimedia Commons, Google 갤러리의 public domain 이미지)이다. 합성 이미지는 압축률이 비현실적이라 사진과 함께 쓴다.

### 결과

- 긍정: 애니메이션·메타데이터·악성 입력까지 라이선스 걱정 없이 커버한다.
- 긍정: 공식 벡터로 libwebp와의 적합성을 확인하면서도 저장소에는 넣지 않는다(라이선스 미확인 리스크 회피).
- 부정: 생성·변조·내려받기 스크립트와 manifest를 유지해야 한다.
- 부정: 적합성 테스트는 네트워크(최초 1회)가 필요하다.
- 부정: 자체 생성 샘플의 기대값은 "libwebp가 만든 결과"라서, libwebp 자체의 버그는 잡지 못한다.

### 확인 방법

- Phase 1: manifest의 모든 파일 sha256이 일치하고, `SOURCES.md`가 manifest에서 다시 생성해도 바뀌지 않는다.
- Phase 1: libwebp-test-data `.pam` md5 비교가 서버 디코딩 결과로 통과한다.
- 정기적으로: 커밋 기준(크기, 라이선스)을 넘는 파일이 없는지 확인한다.

## 선택지별 장단점

### 1. 자체 생성 커밋 + 외부 고정 다운로드 (채택)

- 장점: 커버리지, 라이선스, 크기의 균형.
- 단점: 스크립트와 manifest 유지.

### 2. 외부 세트 복사

- 장점: 네트워크 없이 모든 테스트 실행.
- 단점: libwebp-test-data만 23.5MB이고 라이선스 파일이 없다. 애니메이션·메타데이터는 여전히 없다.

### 3. 자체 생성만

- 장점: 가장 단순하고 라이선스 문제가 없다.
- 단점: 공식 적합성 벡터와 비교하지 못한다.

### 4. 테스트 실행 시 전부 생성

- 장점: 저장소에 바이너리가 없다.
- 단점: 테스트 환경에 libwebp CLI가 필요하고, 도구 버전이 바뀌면 입력이 바뀐다(재현성 저하).

## 참고

- 출처별 라이선스, 생성 명령, 변조 케이스, RFC 9649 절 대응표: [docs/research/0005-test-samples.md](../research/0005-test-samples.md)
