# deploy

서버 컨테이너 이미지와 실행 구성을 둔다.

| 파일 | 내용 |
|---|---|
| [Dockerfile](Dockerfile) | musl 정적 바이너리 + `scratch` 이미지(ADR-0012). 빌드 컨텍스트는 저장소 루트 |
| [compose.yaml](compose.yaml) | 사이드카 예시(공유 볼륨의 UDS). compose로는 미검증 |

seccomp 프로파일은 Phase 5에서 추가한다([docs/roadmap.md](../docs/roadmap.md)).

## 빌드

```sh
# 저장소 루트에서
docker build -f deploy/Dockerfile -t webp-imageio-server:dev .
```

이미지 안의 파일: `/webp-imageio-server`(실행 파일), `/run/webp-imageio/`(소켓 디렉터리, 소유자 65532), `/licenses/`(프로젝트 LICENSE, libwebp `COPYING`·`PATENTS`, Rust 의존성 고지).

## 실행 (격리 옵션)

```sh
docker run -d --name webp-server \
  --read-only --network none --cap-drop ALL --security-opt no-new-privileges \
  --memory 1g --memory-swap 1g --cpus 2 --pids-limit 128 \
  -v webp-socket:/run/webp-imageio \
  webp-imageio-server:dev
```

- 기본 사용자는 `65532:65532`(non-root), 소켓 모드는 `0660`이다. 클라이언트는 같은 볼륨을 마운트하고 그룹 65532를 쓴다.
- 호스트 디렉터리를 바인드 마운트해 호스트 프로세스가 붙게 하려면 `--user "$(id -u):$(id -g)"`로 소유자를 맞춘다.
- 설정은 환경변수(`-e WEBP_SERVER_...`)나 명령줄 인자로 준다. 키: [docs/design/grpc-api.md §3.1](../docs/design/grpc-api.md).
- 개발용 TCP: `--network` 제한을 풀고 `-e WEBP_SERVER_TCP_ADDR=0.0.0.0:50051 -p 127.0.0.1:50051:50051`. 컨테이너 안에서는 `0.0.0.0`에 바인드해야 포트 게시가 동작하고, 호스트 쪽은 `127.0.0.1`로만 연다.
- **UDS 경로 길이**: AF_UNIX 소켓 경로는 약 107바이트를 넘을 수 없다. 깊은 임시 디렉터리에 소켓을 두면 연결이 실패한다(기본 `/run/webp-imageio/webp.sock`은 문제없다).

## 스모크 테스트

실행 중인 컨테이너에 UDS로 붙어 버전, 헬스, 정지 이미지, 4K 왕복, bomb 거부를 확인한다(`server/tests/container.rs`).

```sh
SOCKDIR=$(mktemp -d /tmp/wps.XXXXXX)
docker run -d --name webp-smoke --read-only --network none --cap-drop ALL --security-opt no-new-privileges \
  --memory 1g --cpus 2 --user "$(id -u):$(id -g)" -v "$SOCKDIR:/run/webp-imageio" webp-imageio-server:dev
(cd server && WEBP_IMAGEIO_TEST_UDS="$SOCKDIR/webp.sock" cargo test --test container -- --ignored)
docker rm -f webp-smoke
```

## 메모리 예산 측정

`server/scripts/measure-memory.sh <메모리 제한> <동시 요청 수> <변 픽셀>` — 결과와 해석은 [docs/research/0007-server-memory-budget.md](../docs/research/0007-server-memory-budget.md).
