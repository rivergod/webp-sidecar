//! libwebp를 링크한 WebP gRPC 사이드카 서버(`webp.v1`).
//!
//! - 계약: proto/webp/v1, 설명은 docs/design/grpc-api.md
//! - 구성: [`config`](설정 해석), [`webp`](libwebp FFI 경계, `unsafe`는 여기에만), [`pool`](출력 버퍼 풀),
//!   [`service`](gRPC 서비스)
#![warn(missing_docs)]
#![warn(clippy::undocumented_unsafe_blocks)]

pub mod admission;
pub mod config;
pub mod pool;
pub mod service;
pub mod webp;

/// prost/tonic이 proto/webp/v1에서 생성한 코드. 문서는 proto 주석에서 온다.
#[allow(missing_docs, clippy::all, clippy::pedantic)]
pub mod proto {
    tonic::include_proto!("webp.v1");
}

/// HTTP/2 스트림 수신 창(클라이언트 → 서버). 단위: 바이트.
///
/// 서버는 동시 처리 슬롯을 잡은 뒤에야 요청 입력을 읽는다. 슬롯을 기다리는 요청이 보낸 데이터는 읽히지 않은 채 연결 수신
/// 창을 차지한다. 연결 창이 대기 요청들로 차면 슬롯을 가진 요청도 더 보낼 수 없어 끝나지 못하고, 슬롯이 풀리지 않아 모두
/// 멈춘다(교착, docs/research/0012-gif-conversion-load.md). 그래서 연결 창을 "최대 동시 스트림 × 스트림 창"보다 넉넉히
/// 크게 둔다(아래 불변식). hyper 기본값은 연결 창과 스트림 창이 둘 다 1 MiB라 대기 요청 하나로도 교착이 났다.
pub const H2_STREAM_WINDOW_BYTES: u32 = 256 * 1024;
/// 연결 하나의 최대 동시 스트림 수. 넘는 요청은 클라이언트가 자기 쪽에서 기다린다.
pub const H2_MAX_CONCURRENT_STREAMS: u32 = 64;
/// HTTP/2 연결 수신 창. 단위: 바이트. 대기 스트림 전부(최대 16 MiB)를 받고도 같은 크기의 여유가 남는다.
pub const H2_CONNECTION_WINDOW_BYTES: u32 = 32 * 1024 * 1024;
const _: () = assert!(
    H2_CONNECTION_WINDOW_BYTES as u64 >= 2 * H2_MAX_CONCURRENT_STREAMS as u64 * H2_STREAM_WINDOW_BYTES as u64,
    "the HTTP/2 connection window must exceed what waiting streams can hold"
);

/// 서비스와 표준 헬스체크(`grpc.health.v1.Health`)를 등록한 라우터를 만든다. 헬스 상태는 SERVING이다.
///
/// 리스너(UDS, TCP)마다 한 번씩 부른다. 같은 [`service::WebpService`]를 복제해 넘기면 상한과 동시 처리 슬롯을 공유한다.
pub async fn router(service: service::WebpService) -> tonic::transport::server::Router {
    use proto::web_p_service_server::WebPServiceServer;
    let (reporter, health) = tonic_health::server::health_reporter();
    reporter.set_serving::<WebPServiceServer<service::WebpService>>().await;
    tonic::transport::Server::builder()
        .initial_stream_window_size(H2_STREAM_WINDOW_BYTES)
        .initial_connection_window_size(H2_CONNECTION_WINDOW_BYTES)
        .max_concurrent_streams(H2_MAX_CONCURRENT_STREAMS)
        .add_service(health)
        .add_service(WebPServiceServer::new(service))
}

/// TCP 리스너를 tonic incoming으로 감싼다(ADR-0003 TCP 옵션). 받은 연결마다 `TCP_NODELAY`를 켠다.
///
/// tonic은 직접 넘긴 incoming에는 소켓 옵션을 걸지 않는다. Nagle 알고리즘이 켜져 있으면 응답의 작은 HTTP/2 프레임
/// (헤더, 트레일러)이 클라이언트의 지연 ACK를 기다려 요청마다 약 40 ms가 늦어진다(Phase 4 벤치에서 발견).
pub fn tcp_incoming(listener: tokio::net::TcpListener) -> tonic::transport::server::TcpIncoming {
    tonic::transport::server::TcpIncoming::from(listener).with_nodelay(Some(true))
}

#[cfg(test)]
mod tests {
    use super::*;
    use tokio_stream::StreamExt;

    #[tokio::test]
    async fn tcp_incoming_accepted_connection_has_nodelay() {
        // Arrange
        let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let addr = listener.local_addr().unwrap();
        let mut incoming = tcp_incoming(listener);
        let _client = tokio::net::TcpStream::connect(addr).await.unwrap();

        // Act
        let accepted = incoming.next().await.unwrap().unwrap();

        // Assert
        assert!(
            accepted.nodelay().unwrap(),
            "TCP_NODELAY must be set on accepted connections"
        );
    }
}
