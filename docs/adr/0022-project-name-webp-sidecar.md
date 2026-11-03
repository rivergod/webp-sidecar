# 0022. 프로젝트 이름을 `webp-sidecar`로 바꾸고 구성 요소 이름을 한 규칙으로 맞춘다

- 상태: accepted
- 날짜: 2026-11-03
- 결정자: Ilkang Na
- 관련 문서: 이름을 정했던 ADR — [ADR-0003](0003-transport.md)(기본 소켓 경로), [ADR-0010](0010-maven-coordinates.md)(Maven 좌표), [ADR-0012](0012-server-runtime-config.md)(서버 이름, 소켓 경로), [ADR-0013](0013-client-package-and-imageio-mapping.md)(Java 패키지·모듈), [ADR-0014](0014-animation-and-metadata-mapping.md)(native 메타데이터 포맷 이름), [ADR-0020](0020-imageio-config-injection.md)(클라이언트 설정 키). 이 ADR은 그 ADR들의 이름 항목만 대체한다

## 맥락

- 프로젝트 이름 `webp-imageio-external-server-proto`는 길다. 결정자 지시(2026-11-03): `webp-sidecar`로 하고 관련된 이름을 모두 바꾼다. Java 패키지는 `net.rivergod.webp.sidecar`로 한다(결정자 선택).
- 이름은 여러 ADR에 흩어져 있다(위 관련 문서).
- 아직 외부에 배포하지 않았다(로드맵 "첫 배포 전 결정"). 지금 바꾸면 호환 부담이 없다.
- 이미 쓰이는 이름이 있다(확인 2026-11-03, 일반 웹 검색):
  - `webp-imageio`: `com.github.gotson:webp-imageio`(Sejda/Luciad 계열), `io.github.darkxanter:webp-imageio`(이 프로젝트의 벤치 비교 대상), `com.github.nintha:webp-imageio-core`
  - `imageio-webp`: TwelveMonkeys, gotson/nightmonkeys
  - `webp-sidecar`라는 프로젝트는 찾지 못했다. 등록 저장소(Maven Central, crates.io)는 따로 확인하지 않았다(미확인).

## 결정 동인

1. 짧고, 구조(libwebp를 격리한 사이드카)를 드러낸다.
2. 이미 쓰이는 WebP ImageIO 플러그인 이름과 헷갈리지 않는다.
3. 모든 구성 요소의 이름이 한 규칙(`webp-sidecar` + 구성 요소)을 따른다.
4. 배포 전이므로 옛 이름 별칭 없이 한 번에 바꾼다.

## 검토한 선택지

1. **`webp-sidecar`** (구성 요소마다 접미사)
2. `imageio-webp-sidecar`
3. `webpbox`
4. 그대로 둔다

## 결정

**선택지 1을 택한다.** 이름 대응:

| 대상 | 전 | 후 |
|---|---|---|
| 저장소·Gradle 루트 프로젝트 | `webp-imageio-external-server-proto` | `webp-sidecar` |
| Maven 좌표(플러그인 jar) | `net.rivergod:webp-imageio-external-server-proto` | `net.rivergod:webp-sidecar-imageio` |
| Java 패키지·JPMS 모듈 | `net.rivergod.webp.imageio` | `net.rivergod.webp.sidecar` |
| 생성 proto/gRPC 코드(export하지 않음) | `net.rivergod.webp.imageio.proto.v1` | `net.rivergod.webp.sidecar.proto.v1` |
| 벤치 패키지(배포하지 않음) | `net.rivergod.webp.imageio.bench` | `net.rivergod.webp.sidecar.bench` |
| ImageIO native 메타데이터 포맷 | `net_rivergod_webp_imageio_1.0` | `net_rivergod_webp_sidecar_1.0` |
| 클라이언트 설정 키 | `webp.imageio.*` / `WEBP_IMAGEIO_*` | `webp.sidecar.*` / `WEBP_SIDECAR_*` |
| 서버 크레이트·바이너리·컨테이너 이미지 | `webp-imageio-server`(라이브러리 `webp_imageio_server`) | `webp-sidecar`(라이브러리 `webp_sidecar`) |
| 기본 소켓 경로 | `/run/webp-imageio/webp.sock` | `/run/webp-sidecar/webp.sock` |
| seccomp 프로파일 파일 | `deploy/seccomp/webp-imageio-server.json` | `deploy/seccomp/webp-sidecar.json` |
| 퍼저 크레이트·이미지·캐시 볼륨 | `webp-imageio-server-fuzz`, `webp-imageio-fuzz:*`, `webp-imageio-fuzz-cache` | `webp-sidecar-fuzz`, `webp-sidecar-fuzz:*`, `webp-sidecar-fuzz-cache` |
| 서버 테스트 환경변수 | `WEBP_IMAGEIO_TEST_*` | `WEBP_SIDECAR_TEST_*` |

바꾸지 않는 것:

- groupId `net.rivergod`(ADR-0010).
- 서버 설정 키 `WEBP_SERVER_*`와 명령줄 인자(옛 이름이 들어 있지 않다). proto 패키지 `webp.v1`(와이어 계약, 이름과 무관).
- 기록: 확정된 ADR의 본문, `docs/research/`, `bench/results/`. 그때의 이름으로 남기고, 이 표로 옮겨 읽는다.
- 샘플 데이터의 정체성: 생성 seed(`webp-imageio-samples/v1`), 생성 샘플 안에 들어간 문자열(EXIF Software, XMP 제목). 바꾸면 생성 샘플과 기대 해시가 모두 바뀐다.
- 다른 프로젝트의 이름: darkxanter `webp-imageio`(벤치 의존성과 jar 이름), TwelveMonkeys `imageio-webp`.
- 로컬 작업 폴더 이름. 사람이 바꾼다(작업 중인 도구의 경로가 바뀌기 때문이다).
- 옛 이름의 별칭(설정 키, 이미지 태그)은 두지 않는다.

### 결과

- 긍정: 이름이 짧고 한 규칙을 따른다. 다른 WebP ImageIO 플러그인과 헷갈리지 않는다.
- 부정: 공개 API(패키지, 설정 키, 메타데이터 포맷 이름, 기본 소켓 경로)가 바뀐다. 배포 전이라 저장소 밖의 사용자는 없다고 본다(추정).
- 부정: 기록 문서(확정 ADR, research, bench/results)와 지금 코드의 이름이 다르다.
- 부정: 서버 이미지 이름이 바뀌어 로컬 태그(`webp-imageio-server:*`)와 퍼저 이미지를 다시 만들어야 한다.

### 확인 방법

- 기록 밖(코드, 빌드, 스크립트, 현재 문서)에서 옛 이름이 위 "바꾸지 않는 것"으로만 남는다(`git grep`).
- AGENTS.md 명령표의 검사가 모두 통과한다: proto lint, 서버 검사·테스트·라이선스(고지 재생성), client-jvm 단위·통합 테스트, 서버 이미지 빌드, 격리 확인, 샘플 검사, 벤치 연결 확인(`bench/run.sh smoke`), 퍼저 빌드.

## 선택지별 장단점

### 1. `webp-sidecar` (택함)

- 장점: 짧다. 구조가 드러난다. 서버(`webp-sidecar`)와 플러그인(`webp-sidecar-imageio`)을 한 규칙으로 부른다. 같은 이름의 프로젝트를 찾지 못했다.
- 단점: ImageIO 플러그인이라는 점은 이름만으로 보이지 않는다(artifactId의 `-imageio`가 보탠다).

### 2. `imageio-webp-sidecar`

- 장점: ImageIO 플러그인이라는 점이 분명하다.
- 단점: 길다. 이미 있는 `imageio-webp`와 헷갈린다.

### 3. `webpbox`

- 장점: 가장 짧다.
- 단점: 무엇을 하는지 덜 드러난다.

### 4. 그대로 둔다

- 장점: 바꿀 것이 없다.
- 단점: 이름이 길고 `proto`(프로토타입)가 이름에 남는다. 배포 뒤에 바꾸면 호환 비용이 생긴다.
