//! libwebp를 링크한 WebP gRPC 사이드카 서버(`webp.v1`).
//!
//! - 계약: proto/webp/v1, 설명은 docs/design/grpc-api.md
//! - 구성: [`config`](설정 해석), [`webp`](libwebp FFI 경계, `unsafe`는 여기에만), [`service`](gRPC 서비스)
#![warn(missing_docs)]
#![warn(clippy::undocumented_unsafe_blocks)]

pub mod config;
pub mod service;
pub mod webp;

/// prost/tonic이 proto/webp/v1에서 생성한 코드. 문서는 proto 주석에서 온다.
#[allow(missing_docs, clippy::all, clippy::pedantic)]
pub mod proto {
    tonic::include_proto!("webp.v1");
}

/// 서비스와 표준 헬스체크(`grpc.health.v1.Health`)를 등록한 라우터를 만든다. 헬스 상태는 SERVING이다.
///
/// 리스너(UDS, TCP)마다 한 번씩 부른다. 같은 [`service::WebpService`]를 복제해 넘기면 상한과 동시 처리 슬롯을 공유한다.
pub async fn router(service: service::WebpService) -> tonic::transport::server::Router {
    use proto::web_p_service_server::WebPServiceServer;
    let (reporter, health) = tonic_health::server::health_reporter();
    reporter.set_serving::<WebPServiceServer<service::WebpService>>().await;
    tonic::transport::Server::builder()
        .add_service(health)
        .add_service(WebPServiceServer::new(service))
}
