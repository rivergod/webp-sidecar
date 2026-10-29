# deploy

서버 컨테이너 이미지와 실행 구성을 둔다.

| 파일 | 내용 |
|---|---|
| [Dockerfile](Dockerfile) | musl 정적 바이너리 + `scratch` 이미지(ADR-0012). 빌드 컨텍스트는 저장소 루트 |
| [compose.yaml](compose.yaml) | 사이드카 예시(공유 볼륨의 UDS). compose로는 미검증 |
| [seccomp/webp-imageio-server.json](seccomp/webp-imageio-server.json) | seccomp 허용 목록 프로파일(x86_64). 아래 "seccomp 프로파일" |
| [scripts/verify-hardening.sh](scripts/verify-hardening.sh) | 격리 옵션이 실제로 적용됐는지 확인. 아래 "격리 적용 확인" |

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
  --security-opt seccomp=deploy/seccomp/webp-imageio-server.json \
  --memory 1g --memory-swap 1g --cpus 2 --pids-limit 128 \
  -v webp-socket:/run/webp-imageio \
  webp-imageio-server:dev
```

- 기본 사용자는 `65532:65532`(non-root), 소켓 모드는 `0660`이다. 클라이언트는 같은 볼륨을 마운트하고 그룹 65532를 쓴다.
- 호스트 디렉터리를 바인드 마운트해 호스트 프로세스가 붙게 하려면 `--user "$(id -u):$(id -g)"`로 소유자를 맞춘다.
- 설정은 환경변수(`-e WEBP_SERVER_...`)나 명령줄 인자로 준다. 키: [docs/design/grpc-api.md §3.1](../docs/design/grpc-api.md).
- **메모리 제한을 바꾸면 메모리 예산도 함께 바꾼다**: 서버는 `WEBP_SERVER_MAX_MEMORY_BYTES`(기본 768 MiB, `--memory 1g`의 75%) 안에서만 요청을 받는다([ADR-0016](../docs/adr/0016-memory-budget-admission.md)). 컨테이너 제한만 올리면 큰 인코딩은 계속 거부된다. 예: `--memory 4g`이면 `-e WEBP_SERVER_MAX_MEMORY_BYTES=3221225472`(3 GiB).
- 개발용 TCP: `--network` 제한을 풀고 `-e WEBP_SERVER_TCP_ADDR=0.0.0.0:50051 -p 127.0.0.1:50051:50051`. 컨테이너 안에서는 `0.0.0.0`에 바인드해야 포트 게시가 동작하고, 호스트 쪽은 `127.0.0.1`로만 연다.
- **UDS 경로 길이**: AF_UNIX 소켓 경로는 약 107바이트를 넘을 수 없다. 깊은 임시 디렉터리에 소켓을 두면 연결이 실패한다(기본 `/run/webp-imageio/webp.sock`은 문제없다).

## seccomp 프로파일

[seccomp/webp-imageio-server.json](seccomp/webp-imageio-server.json)은 허용 목록이다. 목록 밖의 시스템 콜은 `EPERM`으로 실패한다. 경로는 저장소 루트 기준이고, Docker CLI는 파일을 읽어 내용을 보낸다.

| 항목 | 내용 |
|---|---|
| 허용 | 서버가 쓰는 50개(UDS/TCP 서비스, tokio 런타임, musl 할당자, 시그널 종료) + runc 초기화가 서버 실행 전에 부르는 5개(`fstatfs`, `getdents64`, `openat`, `openat2`, `statx`) |
| 기본 동작 | `SCMP_ACT_ERRNO`(EPERM). 프로세스를 죽이지 않고 호출만 실패시킨다. 테스트가 덮지 못한 드문 경로(예: 메모리 부족 때 할당자가 부르는 호출)에서 서버 전체가 죽지 않게 하려는 선택이다. 침해 탐지를 우선하면 `SCMP_ACT_KILL_PROCESS`로 바꿀 수 있다 |
| 아키텍처 | `SCMP_ARCH_X86_64`만. 32비트 ABI(x86, x32) 호출은 libseccomp 기본 동작으로 막힌다. aarch64용 목록은 만들지 않았다(미확인) |
| 전제 | `--security-opt no-new-privileges`와 함께 쓴다. 이 옵션이 없으면 runc가 필터를 더 일찍 걸어 초기화가 실패한다(확인함) |
| 검증(2026-10-29, Docker 29.1.3, runc 1.4.0) | 이 프로파일을 건 컨테이너로 client-jvm 통합 테스트 20개(`./gradlew :client-jvm:integrationTest`가 기본으로 적용), 벤치 검증(`bench/run.sh verify`), 컨테이너 스모크 테스트가 통과하고 SIGTERM으로 정상 종료한다. 목록 밖 호출은 막힌다(같은 프로파일로 `uname`, `sh`가 실패) |

목록을 다시 만드는 방법(서버 코드나 의존성을 바꾼 뒤):

1. 이미지에서 꺼낸 서버 바이너리를 호스트에서 `strace -f -o trace.txt`로 띄우고 부하를 건다. 예: `bench/run.sh` 검증 테스트, 짧은 JMH 인코딩·TCP 실행. 나온 이름이 서버 목록이다.
2. 같은 목록에 `"defaultAction": "SCMP_ACT_LOG"`를 준 프로파일로 통합 테스트를 돌린다: `./gradlew :client-jvm:integrationTest -PwebpSeccompProfile=<LOG 프로파일>`. 목록 밖 호출은 막히지 않고 커널 감사 로그에 남는다: `journalctl -k | grep type=1326`(syscall 번호, 프로세스 이름).
3. 로그에 나온 호출을 이유와 함께 넣고, 기본 동작을 `SCMP_ACT_ERRNO`로 되돌려 통합 테스트를 다시 돌린다.

**LOG 모드만 믿지 않는다.** runc의 `getdents64`(exec fd 정리)는 LOG 모드 감사 로그에 나오지 않았는데 ERRNO 모드에서는 막혀 컨테이너가 뜨지 않았다(원인 미확인). ERRNO로 막힌 호출은 감사 로그에도 남지 않으므로, 마지막 확인은 반드시 최종 프로파일(ERRNO)로 컨테이너 기동과 통합 테스트 전체를 돌려서 한다. runc를 올린 뒤에도 같다.

## 격리 적용 확인

```sh
deploy/scripts/verify-hardening.sh            # 저장소 루트에서. 이미지 기본값 webp-imageio-server:dev
```

위 "실행 (격리 옵션)"과 같은 옵션으로 컨테이너를 띄우고 23개 항목을 확인한다. 설정값만 보지 않고, 호스트에서 본 서버 프로세스의 실제 상태를 함께 본다.

| 구분 | 확인 항목 |
|---|---|
| 설정값(`docker inspect`) | read-only rootfs, 네트워크 `none`, `cap-drop ALL`, 메모리·스왑 1 GiB, CPU 2, pids 128, 이미지 사용자 65532 |
| 프로세스(`/proc/<pid>/status`) | uid·gid 65532(real, effective, saved, fs), capability(effective, permitted, bounding) 모두 0, `NoNewPrivs` 1, `Seccomp` 2(필터) |
| 마운트·네트워크 | `/`는 `ro`, `/run/webp-imageio`만 `rw`. 네트워크 인터페이스는 `lo`뿐 |
| cgroup v2 | `memory.max` 1 GiB, `memory.swap.max` 0, `cpu.max` `200000 100000`, `pids.max` 128 |

2026-10-29 실행 결과: 23개 모두 통과(Docker 29.1.3, runc 1.4.0, cgroup v2). 메모리 상한이 실제로 프로세스를 멈추는 것은 메모리 예산 측정에서 확인했다(OOMKilled, [research/0007](../docs/research/0007-server-memory-budget.md)).

## 스모크 테스트

실행 중인 컨테이너에 UDS로 붙어 버전, 헬스, 정지 이미지, 4K 왕복, bomb 거부를 확인한다(`server/tests/container.rs`).

```sh
SOCKDIR=$(mktemp -d /tmp/wps.XXXXXX)
docker run -d --name webp-smoke --read-only --network none --cap-drop ALL --security-opt no-new-privileges \
  --security-opt seccomp=deploy/seccomp/webp-imageio-server.json \
  --memory 1g --cpus 2 --user "$(id -u):$(id -g)" -v "$SOCKDIR:/run/webp-imageio" webp-imageio-server:dev
(cd server && WEBP_IMAGEIO_TEST_UDS="$SOCKDIR/webp.sock" cargo test --test container -- --ignored)
docker rm -f webp-smoke
```

## 메모리 예산 측정

`server/scripts/measure-memory.sh <메모리 제한> <동시 요청 수> <변 픽셀>` — 결과와 해석은 [docs/research/0007-server-memory-budget.md](../docs/research/0007-server-memory-budget.md).
