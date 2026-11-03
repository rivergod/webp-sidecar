//! 실행 중인 서버 컨테이너에 UDS로 붙는 스모크 테스트. 컨테이너가 필요하므로 기본 실행에서 제외한다.
//!
//! 실행: deploy/README.md "스모크 테스트" (WEBP_SIDECAR_TEST_UDS에 소켓 경로를 준다)
//! `cargo test --test container -- --ignored`

mod common;

use common::{channel_at, decode, decode_options, encode, pattern};
use tonic::Code;
use webp_sidecar::proto as pb;
use webp_sidecar::proto::web_p_service_client::WebPServiceClient;

const STILL: &[u8] = include_bytes!("../../spikes/testdata/still.webp");
const BOMB: &[u8] = include_bytes!("../../spikes/testdata/bomb_16384.webp");

fn socket() -> std::path::PathBuf {
    std::env::var_os("WEBP_SIDECAR_TEST_UDS")
        .expect("set WEBP_SIDECAR_TEST_UDS to the container socket path")
        .into()
}

#[tokio::test]
#[ignore = "requires a running server container (deploy/README.md)"]
async fn container_serves_info_health_decode_and_rejects_bomb() {
    // given
    let channel = channel_at(socket()).await;
    let mut client = WebPServiceClient::new(channel.clone());
    let mut health = tonic_health::pb::health_client::HealthClient::new(channel);

    // when / then: 버전, 헬스, 정지 이미지, 4K 왕복, bomb 거부를 한 연결에서 확인한다.
    let info = client.get_info(pb::GetInfoRequest {}).await.unwrap().into_inner();
    assert_eq!(info.libwebp.unwrap().decoder, "1.6.0");
    let status = health
        .check(tonic_health::pb::HealthCheckRequest { service: String::new() })
        .await
        .unwrap()
        .into_inner()
        .status;
    assert_eq!(
        status,
        tonic_health::pb::health_check_response::ServingStatus::Serving as i32
    );
    let (header, _) = decode(&mut client, STILL, decode_options(STILL)).await.unwrap();
    assert_eq!((header.width, header.height), (4, 3));
    let pixels = pattern(3840, 2160, true);
    let lossless = pb::EncodeParams {
        lossless: true,
        quality: None,
        method: None,
    };
    let webp = encode(&mut client, &pixels, 3840, 2160, lossless).await.unwrap();
    let (_, decoded) = decode(&mut client, &webp, decode_options(&webp)).await.unwrap();
    assert!(decoded == pixels);
    let bomb = decode(&mut client, BOMB, decode_options(BOMB)).await.unwrap_err();
    assert_eq!(bomb.code(), Code::ResourceExhausted);
}
