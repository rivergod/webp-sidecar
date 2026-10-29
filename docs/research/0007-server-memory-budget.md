# 0007. 서버 메모리 예산 측정

- 상태: 초안
- 작성일/확인일: 2026-10-23, ADR-0016 재측정 2026-10-29
- 관련 ADR: ADR-0012, ADR-0016

## 요약

- ADR-0012 기본값(메모리 1 GiB, CPU 2, 동시 처리 2, `max_pixels` 8192²)에서 **디코딩은 안전하고 인코딩은 OOM**이다.
  - 8192² 정지 이미지 디코딩: 2개 동시에 최고 576–661 MiB, 통과.
  - 8192² 인코딩: 1개만으로도 OOM. 4096² 인코딩도 2개 동시면 OOM.
- 인코딩 메모리의 대부분은 libwebp 인코더 자체다. gRPC 없이 프로세스 안에서 인코딩만 해도 8192² 무작위 잡음 입력의 최고 RSS가 lossy 1.27 GiB, lossless 2.69 GiB다.
- 측정값으로 본 대략의 픽셀당 메모리(입력·출력 버퍼 포함, 서버 컨테이너 기준): 정지 이미지 디코딩 약 5 B/px, 인코딩 약 31–36 B/px.
- 애니메이션(Phase 3에서 측정, 3프레임): 디코딩 약 8.6 B/px(libwebp `WebPAnimDecoder`의 캔버스 버퍼 2개 — v1.6.0 `src/demux/anim_decode.c` 137–141행 — 와 전송 버퍼), lossless 인코딩 약 50–52 B/px. 기본값에서는 4096² 애니메이션 인코딩도 동시 2개면 OOM이다.
- **ADR-0016(메모리 예산) 구현 뒤 다시 쟀다(2026-10-29).** 같은 12개 조합(8192²·4096², 동시 2, 1 GiB)에서 OOM이 0건이다. 전에 OOM이던 작업은 차례로 처리되거나(4096² 인코딩 2개), 일을 시작하기 전에 `RESOURCE_EXHAUSTED`로 거부된다(8192² 인코딩, 4096² 애니메이션 인코딩). 아래 "ADR-0016 구현 뒤".

## 방법

- 서버 이미지: `deploy/Dockerfile`(musl 정적, scratch), libwebp 1.6.0.
- 컨테이너 옵션: `--read-only --network none --cap-drop ALL --security-opt no-new-privileges --memory <제한> --memory-swap <제한> --cpus 2`.
- 작업마다 새 컨테이너를 띄우고 요청을 보낸 뒤 cgroup v2 `memory.peak`를 읽는다. 서버가 OOM으로 죽으면 `docker inspect`의 `OOMKilled`로 판정한다.
- 입력
  - 디코딩: 단색 8192² 이미지를 같은 libwebp로 인코딩한 파일(압축 입력이 작아 `max_input_bytes` 안에 들어간다). 디코딩 결과는 크기만 같으면 내용과 무관하게 같은 버퍼를 쓴다.
  - 인코딩: `server/tests/common/mod.rs`의 `pattern(side, side, true)`(그라디언트 + 작은 잡음 + alpha 패턴).
- 재현: `server/scripts/measure-memory.sh <메모리 제한> <동시 요청 수> <변 픽셀>` (하네스: `server/tests/memory_budget.rs`)
- libwebp 단독 측정: `server/examples/encode_rss.rs` (무작위 잡음, 호스트 glibc 빌드, `/proc/self/status`의 VmHWM)
- 측정 호스트: x86_64, 32코어, 메모리 61 GB, Docker 29.1.3, cgroup v2 (2026-10-23).

## 결과

### 작업별 최고 메모리 (요청 1개, 제한 4 GiB)

| 작업 (8192 × 8192) | 최고 메모리 | 픽셀당 |
|---|---|---|
| lossless 디코딩 | 292 MiB | 4.6 B |
| lossy 디코딩 | 332 MiB | 5.2 B |
| lossless 인코딩 | 2,058 MiB | 32 B |
| lossy 인코딩 | 2,317 MiB | 36 B |

### 기본값 조합에서의 OOM 여부 (제한 1 GiB)

| 변 | 동시 요청 | lossless 디코딩 | lossy 디코딩 | lossless 인코딩 | lossy 인코딩 |
|---|---|---|---|---|---|
| 8192 | 2 | 576 MiB, 통과 | 661 MiB, 통과 | **OOM** | **OOM** |
| 8192 | 1 | 292 MiB, 통과 | 333 MiB, 통과 | **OOM** | **OOM** |
| 4096 | 2 | 147 MiB, 통과 | 180 MiB, 통과 | **OOM** | **OOM** |
| 4096 | 1 | 75 MiB, 통과 | 91 MiB, 통과 | 520 MiB, 통과 | 594 MiB, 통과 |

### 애니메이션 (3프레임, lossless, 측정 2026-10-23)

- 디코딩 입력: 단색 3프레임 애니메이션(`server/tests/memory_budget.rs`, `decode-anim`). 인코딩 입력: `pattern(side, side, true)` 3프레임(`encode-anim`).

| 작업 | 변 | 동시 요청 | 제한 | 최고 메모리 | 결과 |
|---|---|---|---|---|---|
| 애니메이션 디코딩 | 8192 | 1 | 4 GiB | 551 MiB (8.6 B/px) | 통과 |
| 애니메이션 인코딩 | 8192 | 1 | 4 GiB | 3,342 MiB (52 B/px) | 통과 |
| 애니메이션 디코딩 | 4096 | 1 | 4 GiB | 141 MiB | 통과 |
| 애니메이션 인코딩 | 4096 | 1 | 4 GiB | 842 MiB (50 B/px) | 통과 |
| 애니메이션 디코딩 | 4096 | 2 | 1 GiB | 279 MiB | 통과 |
| 애니메이션 인코딩 | 4096 | 2 | 1 GiB | - | **OOM** |

### libwebp 인코더 단독 (gRPC 없음, 8192² 무작위 잡음)

| 모드 | 입력 직후 VmHWM | 인코딩 후 VmHWM | 출력 크기 |
|---|---|---|---|
| lossy (quality 75) | 264,720 kB | 1,297,280 kB | 40,527,790 B |
| lossless | 264,868 kB | 2,755,408 kB | 201,326,672 B |

### ADR-0016 구현 뒤 (측정 2026-10-29)

`server/scripts/measure-memory.sh 1g 2 8192`, `1g 2 4096`. 서버 기본값: 메모리 예산 768 MiB − 버퍼 풀 128 MiB = 요청 몫 640 MiB. 결과 칸의 추정치는 서버 추정식(docs/design/grpc-api.md §3.3)으로 센 요청 하나의 값이다.

| 작업 | 변 | 요청 하나 추정 | 결과(동시 2) | 최고 메모리 | 전(research 위 표) |
|---|---|---|---|---|---|
| lossless 디코딩 | 8192 | 384 MiB + 입력 | 통과. 둘이 예산에 함께 안 들어 차례로 처리 | 293 MiB | 576 MiB, 통과 |
| lossy 디코딩 | 8192 | 384 MiB + 입력 | 통과. 차례로 처리 | 332 MiB | 661 MiB, 통과 |
| lossless 인코딩 | 8192 | 2,560 MiB | `RESOURCE_EXHAUSTED` | 5 MiB | OOM |
| lossy 인코딩 | 8192 | 2,560 MiB | `RESOURCE_EXHAUSTED` | 6 MiB | OOM |
| 애니메이션 디코딩 | 8192 | 640 MiB + 입력 | `RESOURCE_EXHAUSTED` | 5 MiB | (동시 2는 재지 않음. 하나 551 MiB) |
| 애니메이션 인코딩 | 8192 | 3,840 MiB + 프레임 몫 | `RESOURCE_EXHAUSTED` | 5 MiB | (재지 않음. 하나 3,342 MiB) |
| lossless 디코딩 | 4096 | 96 MiB + 입력 | 통과 | 142 MiB | 147 MiB, 통과 |
| lossy 디코딩 | 4096 | 96 MiB + 입력 | 통과 | 179 MiB | 180 MiB, 통과 |
| lossless 인코딩 | 4096 | 640 MiB | 통과. 차례로 처리 | 520 MiB | OOM |
| lossy 인코딩 | 4096 | 640 MiB | 통과. 차례로 처리 | 594 MiB | OOM |
| 애니메이션 디코딩 | 4096 | 160 MiB + 입력 | 통과 | 277 MiB | 279 MiB, 통과 |
| 애니메이션 인코딩 | 4096 | 960 MiB + 프레임 몫 | `RESOURCE_EXHAUSTED` | 5 MiB | OOM |

- OOM은 0건이다. 거부는 입력을 받기 전(인코딩)이나 머리를 읽은 직후(디코딩)에 나서 서버 메모리가 거의 늘지 않는다(5–6 MiB).
- 추정치와 실측의 여유: 8192² 디코딩은 추정 384 MiB + 입력, 실측 293–332 MiB(하나). 4096² 인코딩은 추정 640 MiB, 실측 520–594 MiB.
- 비용: 8192² 디코딩 둘은 이제 동시에 처리되지 않는다(예산 640 MiB < 2 × 384 MiB). 처리량은 그만큼 준다. 8192² 애니메이션 디코딩은 기본값에서 거부된다(ADR-0016 추정식 10 B/px의 결과, 실측은 8.6 B/px).

## 해석

- 서버가 OOM으로 죽으면 그 순간 처리 중인 모든 요청이 `UNAVAILABLE`로 실패하고 컨테이너 재시작에 기댄다(ADR-0001의 부정적 결과). JVM은 영향을 받지 않는다.
- 동시 처리 수 상한만으로는 작업 종류별 메모리 차이(디코딩 5 B/px 대 인코딩 36 B/px)를 반영하지 못한다. 그래서 메모리 예산 기반 허용(ADR-0016, accepted)을 구현했다(위 "ADR-0016 구현 뒤").
- 측정은 한 호스트, 한 입력 패턴 기준이다. 인코딩 메모리는 입력 내용과 인코더 설정(method 등)에 따라 달라질 수 있다(추정).

## 미확인 항목

- 프레임 수가 많은 애니메이션 인코딩의 메모리(3프레임만 쟀다).
- musl과 glibc 할당기의 차이가 최고 메모리에 주는 영향.
