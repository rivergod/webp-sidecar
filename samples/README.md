# samples

테스트와 벤치마크에 쓰는 이미지를 둔다.

- 모든 파일의 출처, 라이선스, 해시를 [SOURCES.md](SOURCES.md)에 기록한다. 기록 없는 파일은 커밋하지 않는다.
- 세트 구성, 커밋 기준(크기·라이선스), manifest 방식은 [ADR-0006](../docs/adr/0006-test-samples.md)을 따른다. 후보 출처와 라이선스 조사는 [docs/research/0005-test-samples.md](../docs/research/0005-test-samples.md)에 있다.
- 생성 환경(구현 기본값): libwebp 1.6.0 CLI를 소스에서 빌드한 Docker 이미지 안에서 Python(표준 라이브러리) 스크립트로 생성한다. 서버와 같은 libwebp 버전으로 기대값을 만들기 위해서다(로컬 1.5.0 도구는 쓰지 않는다).
