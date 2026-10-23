# bench

JMH 벤치마크. 같은 샘플로 다음을 비교한다.

- 이 프로젝트의 플러그인 (UDS / TCP)
- TwelveMonkeys imageio-webp
- darkxanter webp-imageio
- libwebp Wasm + Chicory — 프로세스 안 Wasm 샌드박스로 돌리는 경쟁 대안([ADR-0001](../docs/adr/0001-isolate-libwebp-in-grpc-sidecar.md)). 벤치 전용 구현이다.
- JDeli — 트라이얼 jar가 있을 때만 선택적으로 포함한다.

프로덕션 모듈과 분리된 별도 모듈로 둔다. Phase 4에서 작성한다([docs/roadmap.md](../docs/roadmap.md)).
