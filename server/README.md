# server

libwebp를 직접 링크한 gRPC 서버. 같은 호스트/파드의 사이드카로 실행하고 Unix Domain Socket을 기본 전송으로 쓴다.

- 구현 언어는 [ADR-0002](../docs/adr/0002-server-language.md)에서 결정한다. 결정 전에는 코드를 두지 않는다.
- 요청마다 `cwebp`/`dwebp` CLI를 실행하는 방식은 쓰지 않는다.
