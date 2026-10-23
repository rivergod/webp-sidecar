# 0010. Maven 좌표: `net.rivergod:webp-imageio-external-server-proto`

- 상태: accepted
- 날짜: 2026-10-23
- 결정자: Ilkang Na
- 관련 문서: [ADR-0005](0005-jdk-language-build.md), [docs/research/0006-license.md](../research/0006-license.md) "Maven 좌표 관련 사실"

## 맥락

ADR-0005는 Maven groupId와 Java 패키지 이름을 사람이 정할 열린 항목으로 남겼다. 클라이언트 jar(ImageIO 플러그인)에 좌표가 필요하다.

## 결정 동인

1. 프로젝트 소유자가 정한 이름을 쓴다.
2. 나중에 Maven Central에 배포할 수 있어야 한다.

## 검토한 선택지

1. **groupId `net.rivergod`, artifactId `webp-imageio-external-server-proto`** (결정자 지정)
2. `io.github.<사용자명>` namespace

## 결정

**선택지 1을 택한다.**

- groupId: `net.rivergod`
- artifactId: `webp-imageio-external-server-proto` — 배포하는 ImageIO 플러그인 jar의 좌표다. `bench/` 모듈은 배포하지 않으므로 좌표가 필요 없다.
- Java 패키지 이름과 JPMS 모듈 이름은 이 ADR에서 정하지 않는다(Phase 2 전에 따로 정한다).

### 결과

- 긍정: 프로젝트 이름과 저장소 이름이 좌표와 일치한다.
- 부정: Maven Central에 배포하려면 `rivergod.net` 도메인에 DNS TXT 레코드를 추가해 namespace를 확인받아야 한다(Sonatype Central 규칙). 도메인을 관리할 수 없으면 좌표를 바꾸는 새 ADR이 필요하다.

### 확인 방법

- Phase 2: Gradle 빌드의 `group`/`artifactId`가 이 값이다.

## 선택지별 장단점

### 1. `net.rivergod`

- 장점: 소유자의 이름 공간.
- 단점: Central 배포 시 도메인 확인 필요.

### 2. `io.github.<사용자명>`

- 장점: GitHub 계정만으로 Central namespace를 받는다.
- 단점: 개인 계정 이름에 묶인다(조직 이름은 지원하지 않음).

## 참고

- Sonatype Central namespace 규칙: [docs/research/0006-license.md](../research/0006-license.md)
