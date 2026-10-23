# 0007. 서버 메모리 예산 측정

- 상태: 초안
- 작성일/확인일: 2026-10-23
- 관련 ADR: ADR-0012, ADR-0016

## 요약

- ADR-0012 기본값(메모리 1 GiB, CPU 2, 동시 처리 2, `max_pixels` 8192²)에서 **디코딩은 안전하고 인코딩은 OOM**이다.
  - 8192² 정지 이미지 디코딩: 2개 동시에 최고 576–661 MiB, 통과.
  - 8192² 인코딩: 1개만으로도 OOM. 4096² 인코딩도 2개 동시면 OOM.
- 인코딩 메모리의 대부분은 libwebp 인코더 자체다. gRPC 없이 프로세스 안에서 인코딩만 해도 8192² 무작위 잡음 입력의 최고 RSS가 lossy 1.27 GiB, lossless 2.69 GiB다.
- 측정값으로 본 대략의 픽셀당 메모리(입력·출력 버퍼 포함, 서버 컨테이너 기준): 정지 이미지 디코딩 약 5 B/px, 인코딩 약 31–36 B/px.
- 애니메이션 디코딩은 아직 구현 전이라 재지 않았다. libwebp `WebPAnimDecoder`는 캔버스 버퍼 2개(각 4 B/px)를 잡는다(libwebp v1.6.0 `src/demux/anim_decode.c` 137–141행).

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

### libwebp 인코더 단독 (gRPC 없음, 8192² 무작위 잡음)

| 모드 | 입력 직후 VmHWM | 인코딩 후 VmHWM | 출력 크기 |
|---|---|---|---|
| lossy (quality 75) | 264,720 kB | 1,297,280 kB | 40,527,790 B |
| lossless | 264,868 kB | 2,755,408 kB | 201,326,672 B |

## 해석

- 서버가 OOM으로 죽으면 그 순간 처리 중인 모든 요청이 `UNAVAILABLE`로 실패하고 컨테이너 재시작에 기댄다(ADR-0001의 부정적 결과). JVM은 영향을 받지 않는다.
- 동시 처리 수 상한만으로는 작업 종류별 메모리 차이(디코딩 5 B/px 대 인코딩 36 B/px)를 반영하지 못한다. 기본값 조정안은 ADR-0016.
- 측정은 한 호스트, 한 입력 패턴 기준이다. 인코딩 메모리는 입력 내용과 인코더 설정(method 등)에 따라 달라질 수 있다(추정).

## 미확인 항목

- 애니메이션 디코딩·인코딩의 메모리(Phase 3에서 측정).
- musl과 glibc 할당기의 차이가 최고 메모리에 주는 영향.
