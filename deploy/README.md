# deploy

서버 컨테이너 이미지와 실행 구성을 둔다.

- Dockerfile, compose 파일
- 격리 강화 설정: 메모리/CPU 제한, read-only 파일시스템, 외부 네트워크 차단, non-root 실행, seccomp 프로파일

Phase 1에서 Dockerfile을, Phase 5에서 seccomp 프로파일을 추가한다([docs/roadmap.md](../docs/roadmap.md)).
