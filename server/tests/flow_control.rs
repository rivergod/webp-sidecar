//! HTTP/2 흐름 제어와 동시 처리 슬롯(ADR-0012 max_concurrency)이 겹쳐도 요청이 멈추지 않는지 확인한다.
//!
//! 서버는 슬롯을 잡은 뒤에야 요청 입력을 읽는다(입력 버퍼를 무한정 잡지 않으려고). 슬롯을 기다리는 요청이 보낸 데이터는
//! 읽히지 않은 채 HTTP/2 연결 수신 창을 차지한다. 한 연결에서 대기 요청들의 데이터가 연결 창을 다 쓰면 슬롯을 가진 요청도
//! 더는 보낼 수 없어 끝나지 못하고, 슬롯이 풀리지 않아 모두 멈춘다(GIF 변환 동시 부하 측정에서 발견, research/0012).

mod common;

use common::{TestServer, encode, pattern};
use std::time::Duration;
use webp_sidecar::config::Limits;
use webp_sidecar::proto as pb;

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn concurrent_large_uploads_on_one_connection_beyond_slots_all_complete() {
    // Arrange: 슬롯 1개, 같은 채널(연결 하나)로 4 MiB 업로드 3개를 동시에 보낸다(서버 연결 창보다 크다).
    let server = TestServer::start_with(Limits::default(), 1).await;
    let channel = server.channel().await;
    let (width, height) = (1024, 1024);
    let pixels = pattern(width, height, false);
    let mut tasks = Vec::new();
    for _ in 0..3 {
        let mut client = pb::web_p_service_client::WebPServiceClient::new(channel.clone());
        let pixels = pixels.clone();
        let params = pb::EncodeParams {
            lossless: false,
            quality: Some(75.0),
            method: None,
        };
        tasks.push(tokio::spawn(async move {
            encode(&mut client, &pixels, width, height, params).await
        }));
    }

    // Act
    let results = tokio::time::timeout(Duration::from_secs(60), futures_join_all(tasks)).await;

    // Assert
    let results = results.expect("uploads stalled: waiting requests exhausted the HTTP/2 connection window");
    for r in results {
        assert!(r.expect("task panicked").is_ok());
    }
}

async fn futures_join_all<T>(tasks: Vec<tokio::task::JoinHandle<T>>) -> Vec<Result<T, tokio::task::JoinError>> {
    let mut out = Vec::with_capacity(tasks.len());
    for t in tasks {
        out.push(t.await);
    }
    out
}
