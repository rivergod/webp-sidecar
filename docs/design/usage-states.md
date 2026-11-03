# 사용 흐름 상태도: 상태 · 전이 · 주체

이 프로젝트를 쓸 때 생기는 일을 상태와 전이로 그린다. 서버 프로세스의 생명주기 하나와, 연산 하나(이미지 한 장 읽기 또는 쓰기)가 앱 → 플러그인 → 서버 → libwebp를 지나 끝나는 모든 갈래를 담는다. 계약과 상한의 정의는 [grpc-api.md](grpc-api.md)가 원본이고, 이 문서는 그것을 한눈에 보는 그림이다.

## 읽는 법

- 전이 이름표는 `주체 · 사건 [조건]` 형식이다. 주체를 적지 않은 전이는 앞 상태의 주체가 이어서 한다.
- 상태 색은 그 상태를 쥔 주체다.
  - 파랑: 앱(애플리케이션 코드, JVM)
  - 초록: ImageIO 플러그인(리더·라이터, gRPC 채널)
  - 주황: 서버(허용 제어, 핸들러, 정리 작업)
  - 보라: libwebp(서버의 blocking 스레드)
  - 회색: 운영자·컨테이너 런타임
  - 빨강: 실패·중단
- 클라이언트 상태와 서버 상태는 실제로는 동시에 진행한다. 그림은 둘을 연산 하나의 경로로 이어 그렸다. 예를 들어 플러그인이 "RPC 시도"에 있는 동안 서버는 "옵션 검사"부터 "응답 전송"까지 지난다.
- "서버 프로세스"는 따로 돈다. 요청 흐름("옵션 검사"부터 "해제"까지)은 서버가 "서비스 중"일 때만 들어간다. 아니면 채널 단계에서 실패한다("WebP server unavailable").
- 서버의 오류 상태는 모두 클라이언트에서 `IIOException`이 된다. 상태별 메시지는 [grpc-api.md §4](grpc-api.md)와 `client-jvm/src/main/java/net/rivergod/webp/sidecar/GrpcErrors.java`에 있다. 혼잡 거부(`UNAVAILABLE` "server busy")만 설정한 횟수만큼 다시 시도한다([ADR-0020](../adr/0020-imageio-config-injection.md), 기본 0회).

## 상태도

```mermaid
stateDiagram-v2
    direction TB

    classDef app fill:#e3f2fd,stroke:#1565c0,color:#0d47a1
    classDef plugin fill:#e8f5e9,stroke:#2e7d32,color:#1b5e20
    classDef server fill:#fff3e0,stroke:#ef6c00,color:#e65100
    classDef lib fill:#f3e5f5,stroke:#6a1b9a,color:#4a148c
    classDef ops fill:#eceff1,stroke:#455a64,color:#263238
    classDef fail fill:#ffebee,stroke:#c62828,color:#b71c1c

    state "서버 프로세스 (운영자 · 컨테이너 런타임 · 서버)" as Proc {
        state "설정 해석 (CLI → env → 기본값)" as Cfg
        state "UDS 바인드 (+TCP는 켰을 때만)" as Bind
        state "서비스 중 · health SERVING" as Serving
        state "유휴 정리" as Maint
        state "종료 중" as Stopping
        state "종료 · exit 0" as Exit0
        state "종료 · exit 1" as Exit1
        state "종료 · exit 2" as Exit2
        state "강제 종료 · OOMKilled" as Killed

        [*] --> Cfg : 운영자 · 컨테이너 시작
        Cfg --> Exit2 : 서버 · 잘못된 설정값
        Cfg --> Bind : 서버 · 설정 OK
        Bind --> Exit1 : 서버 · 바인드 실패, 소켓 아닌 파일
        Bind --> Serving : 서버 · 남은 소켓 지우고 uds_mode(기본 0660)로 리슨
        Serving --> Serving : seccomp · 목록 밖 호출은 EPERM, 프로세스 유지
        Serving --> Maint : 정리 작업 · 주기(풀 유휴 만료의 절반, 기본 15초)
        Maint --> Serving : 만료 버퍼 해제, [유휴이고 변화 있음] malloc_trim
        Serving --> Stopping : 런타임 · SIGTERM / SIGINT
        Stopping --> Exit0 : 서버 · 소켓 파일 삭제
        Stopping --> Exit1 : [TCP 리스너가 실패했었음]
        Serving --> Killed : 런타임 · cgroup 메모리 제한 초과
        Exit0 --> [*]
        Exit1 --> [*]
        Exit2 --> [*]
        Killed --> [*] : 오케스트레이터 · 재시작 정책
    }

    state "SPI 등록 (설정: 리더 → SPI → 시스템 속성 → env → 기본값)" as Reg
    state "입력 판별 · canDecodeInput" as Probe
    state "다른 리더로" as NotWebp
    state "리더 · 클라이언트 준비" as RReady
    state "서버 상한 · GetInfo" as Info
    state "입력 모으기" as Buf
    state "이미지 정보 · GetFeatures" as Feat
    state "쓰기 준비" as WReady
    state "시퀀스 스트림 · EncodeAnimation" as Seq
    state "RPC 시도" as Call
    state "물러섬" as Backoff
    state "응답 받기" as Recv
    state "완료 · BufferedImage 또는 WebP 바이트" as Done
    state "실패 · IIOException" as Fail
    state "중단 · 호출 취소" as Abort

    state "옵션 검사" as Opt
    state "대기 자리 · 머리 64 B 읽기" as Peek
    state "메모리 예산 · 슬롯 요청" as Adm
    state "대기 (메모리 → 슬롯)" as Wait
    state "슬롯 보유 · 입력 받기" as Slot
    state "libwebp 작업 (blocking 스레드)" as Work
    state "응답 전송 (헤더 → 조각)" as Send
    state "오류 상태 응답" as Err
    state "해제 (버퍼 → 풀, 예약·슬롯 반환)" as Rel

    [*] --> Reg : JVM · 서비스 파일 스캔, 또는 앱 · 설정 SPI 수동 등록
    Reg --> Probe : 앱 · ImageIO.read
    Reg --> WReady : 앱 · ImageIO.write / ImageWriter
    Probe --> NotWebp : 레지스트리 · RIFF/WEBP 매직 아님(로컬 검사, 네트워크 없음)
    Probe --> RReady : 매직 맞음
    RReady --> Fail : 플러그인 · 잘못된 클라이언트 설정
    RReady --> Info : 플러그인 · 끝점마다 처음 한 번
    RReady --> Buf : 상한 캐시 있음
    Info --> Buf : 서버 · 상한 응답
    Info --> Fail : 채널 · 서버 없음, 소켓 권한·경로 길이
    Buf --> Fail : 플러그인 · 빈 입력, max_input_bytes 초과
    Buf --> Call : 정지 · 0번 · 기본 파라미터면 Decode 하나
    Buf --> Feat : 그 밖(애니메이션, 1번 이후, 파라미터, 메타데이터)
    Feat --> Fail : 플러그인 · 프레임 번호 범위 밖, 픽셀 상한
    Feat --> Call : 정지 Decode, 순차 DecodeAnimation, 임의 Decode(frame_index)
    WReady --> Fail : 플러그인 · Raster 입력, 빈 영역, 잘못된 method
    WReady --> Call : 플러그인 · GetInfo 뒤 Encode
    WReady --> Seq : 앱 · 첫 writeToSequence, 혼잡 재시도 없음
    Seq --> Seq : 앱 · 다음 프레임(캔버스 크기 같아야 함)
    Seq --> Fail : 플러그인 · 크기 다름, 프레임 0개
    Seq --> Abort : 앱 · reset / dispose / abort
    Seq --> Call : 앱 · endWriteSequence(half-close)

    Call --> Fail : 플러그인 · 연결 실패, 데드라인(기본 30초), 인터럽트
    Call --> Opt : 채널 · 요청 하나 시작 [서버가 서비스 중]
    Opt --> Err : 서버 · 첫 메시지 아님, 형식·파라미터, 입력 크기 초과
    Opt --> Peek : Decode / DecodeAnimation
    Opt --> Adm : GetFeatures / Encode / EncodeAnimation
    Peek --> Err : 서버 · 대기 상한, 머리 시간 초과, 캔버스 초과
    Peek --> Adm : 머리로 메모리 추정
    Adm --> Err : Admission · 추정이 예산보다 큼(즉시), 대기 상한
    Adm --> Slot : 메모리·슬롯 바로 잡음
    Adm --> Wait : 메모리나 슬롯 없음
    Wait --> Slot : Admission · 메모리 → 슬롯 순으로 얻음
    Wait --> Rel : 클라이언트 · 취소 또는 데드라인
    Slot --> Err : 서버 · 조각 위반, max_request_ms, 상한·추가 예약, 프레임 번호
    Slot --> Work : 입력 다 받음
    Work --> Slot : EncodeAnimation · 프레임 하나 끝, 다음 프레임 받기
    Work --> Err : libwebp · 손상, 메모리 부족, 내부 오류·패닉
    Work --> Send : 서버 · 결과 준비
    Send --> Send : DecodeAnimation · 다음 프레임(프레임 사이 시간 검사)
    Send --> Err : 서버 · max_request_ms 초과
    Send --> Recv : 서버 · 헤더와 조각
    Send --> Rel : 서버 · 스트림 끝(OK, 클라이언트가 떠남)
    Recv --> Recv : 앱 · read(i+1)은 열린 스트림에서
    Recv --> Done : 플러그인 · 헤더·바이트 수 검증, 다 받음
    Recv --> Fail : 플러그인 · 데드라인, 헤더 위반
    Recv --> Abort : 앱 · abort / setInput / reset / dispose
    Err --> Rel : 서버 · 상태 보냄
    Err --> Backoff : 플러그인 · UNAVAILABLE server busy [재시도·시간 남음]
    Err --> Fail : 플러그인 · 상태별 메시지(busy 소진, 시간 초과, 손상, 상한, 범위, 내부)
    Backoff --> Call : 100 ms부터 두 배, 최대 1.6초
    Abort --> Rel : 서버 · 취소 받음
    Rel --> [*]
    Done --> [*]
    Fail --> [*]
    NotWebp --> [*]

    class Reg,WReady,Seq,Done app
    class Probe,RReady,Info,Buf,Feat,Call,Backoff,Recv plugin
    class Opt,Peek,Adm,Wait,Slot,Send,Rel,Serving,Maint server
    class Work lib
    class Cfg,Bind,Stopping,Exit0,NotWebp ops
    class Fail,Err,Abort,Exit1,Exit2,Killed fail
```

## 근거

| 영역 | 근거 |
|---|---|
| 서버 시작·종료, 정리 작업 | `server/src/main.rs`(설정 오류 exit 2, 실행 오류 exit 1, SIGTERM·SIGINT, 소켓 정리, 정리 주기), `server/src/config.rs` |
| 허용 제어(대기 상한, 메모리 예산, 슬롯, 시간 상한) | `server/src/admission.rs`, [ADR-0016](../adr/0016-memory-budget-admission.md), [ADR-0017](../adr/0017-output-volume-limits.md) |
| 요청 처리(옵션, 머리 읽기, 상한, 응답 조각, 오류 매핑) | `server/src/service.rs`, `server/src/webp.rs`, [grpc-api.md](grpc-api.md) §2–§4 |
| 출력 버퍼 풀, 유휴 힙 반환 | `server/src/pool.rs`, `server/src/heap.rs`, [ADR-0018](../adr/0018-decode-output-buffer-pool.md), [ADR-0021](../adr/0021-glibc-distroless-image.md) |
| 플러그인(SPI, 입력 판별, 설정 우선순위, RPC 순서, 재시도, 데드라인) | `client-jvm/src/main/java/net/rivergod/webp/sidecar/`(`WebPImageReaderSpi`, `WebPImageReader`, `WebPImageWriter`, `WebPClient`, `StreamingCall`, `AnimationStream`, `GrpcErrors`, `ClientConfig`), [ADR-0013](../adr/0013-client-package-and-imageio-mapping.md), [ADR-0014](../adr/0014-animation-and-metadata-mapping.md), [ADR-0020](../adr/0020-imageio-config-injection.md) |
| 컨테이너(seccomp EPERM, 메모리 제한, 소켓 권한·경로 길이, 재시작 정책) | [deploy/README.md](../../deploy/README.md), `deploy/compose.yaml` |

확인 날짜: 2026-11-03(커밋 `800cf9e` 기준). 코드를 바꿔 상태나 전이가 달라지면 이 그림을 같은 커밋에서 고친다.

## 그림에 없는 것, 정해지지 않은 것

- 종료 중에 처리 중이던 요청을 어떻게 끝내는지는 저장소가 정하지 않았다. tonic의 graceful shutdown 동작을 따르고, 기다리는 시간 상한도 없다(미확인). health 상태도 종료 전에 `NOT_SERVING`으로 바꾸지 않는다.
- 서버에는 `UNAVAILABLE` "server is shutting down" 응답이 정의돼 있지만(`server/src/admission.rs`), 허용 제어의 세마포어를 닫는 코드가 없어 지금은 이 상태에 이르지 않는다. 그래서 그림에 넣지 않았다.
- 소켓 권한(그룹 65532 아님)으로 연결이 거부될 때 클라이언트가 받는 메시지는 문서에 없다. 코드로 보면 "unavailable"로 매핑된다(추정).
- 오케스트레이터의 재시작은 `deploy/compose.yaml`의 `restart: unless-stopped`만 있다. compose 파일은 compose로 실행해 검증하지 않았다(`deploy/compose.yaml` 머리 주석).
