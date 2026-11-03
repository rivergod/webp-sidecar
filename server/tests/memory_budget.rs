//! 메모리 예산 측정 하네스(ADR-0012). 실행 중인 서버 컨테이너에 최악 크기의 작업을 보낸다.
//! 기본 실행에서 제외한다. 사용법: server/scripts/measure-memory.sh
//!
//! 환경변수
//! - `WEBP_SIDECAR_TEST_UDS`: 서버 소켓 경로(필수)
//! - `WEBP_SIDECAR_TEST_OP`: decode-lossless | decode-lossy | encode-lossless | encode-lossy | decode-anim | encode-anim
//!   (애니메이션은 lossless 3프레임)
//! - `WEBP_SIDECAR_TEST_SIDE`: 정사각형 한 변 픽셀 수(기본 8192)
//! - `WEBP_SIDECAR_TEST_CONCURRENT`: 동시에 보낼 요청 수(기본 1)

mod common;

use common::{channel_at, decode, decode_animation, decode_options, encode, encode_animation, pattern};
use webp_sidecar::proto as pb;
use webp_sidecar::proto::web_p_service_client::WebPServiceClient;
use webp_sidecar::webp;

fn env_or(key: &str, default: &str) -> String {
    std::env::var(key).unwrap_or_else(|_| default.to_string())
}

#[tokio::test]
#[ignore = "requires a running server container (server/scripts/measure-memory.sh)"]
async fn memory_budget_worst_case_operation_completes() {
    // given: 작업 입력을 이 프로세스에서 미리 만든다(서버 메모리 측정에 섞이지 않게).
    let socket: std::path::PathBuf = std::env::var_os("WEBP_SIDECAR_TEST_UDS")
        .expect("WEBP_SIDECAR_TEST_UDS")
        .into();
    let op = env_or("WEBP_SIDECAR_TEST_OP", "decode-lossless");
    let side: u32 = env_or("WEBP_SIDECAR_TEST_SIDE", "8192").parse().unwrap();
    let concurrent: usize = env_or("WEBP_SIDECAR_TEST_CONCURRENT", "1").parse().unwrap();
    let pixels = pattern(side, side, true);
    let lossless = op.ends_with("lossless") || op.ends_with("anim");
    let params = webp::EncodeParams {
        lossless,
        quality: None,
        method: None,
    };
    let input = if op == "decode-anim" {
        // 단색 3프레임 애니메이션(압축 입력은 작고, 합성 캔버스는 최대 크기).
        let mut enc = webp::AnimEncoder::new(side, side, 0, 0, &params).unwrap();
        for v in [0x20u8, 0x80, 0xE0] {
            enc.add_frame(&vec![v; pixels.len()], 100).unwrap();
        }
        enc.finish().unwrap()
    } else if op.starts_with("decode") {
        // 디코딩 입력은 max_input_bytes(64 MiB) 안에 들어야 하므로 압축이 잘 되는 단색 이미지를 쓴다.
        let flat = vec![0x80u8; pixels.len()];
        webp::encode_still_rgba(&flat, side, side, &params).unwrap()
    } else {
        Vec::new()
    };

    // when: 같은 작업을 동시에 보낸다.
    let mut tasks = Vec::new();
    for _ in 0..concurrent {
        let (socket, op, pixels, input) = (socket.clone(), op.clone(), pixels.clone(), input.clone());
        tasks.push(tokio::spawn(async move {
            let mut client = WebPServiceClient::new(channel_at(socket).await);
            if op == "decode-anim" {
                decode_animation(&mut client, &input).await.map(|_| ())
            } else if op == "encode-anim" {
                let options = pb::EncodeAnimationOptions {
                    canvas_width: side,
                    canvas_height: side,
                    pixel_format: pb::PixelFormat::Rgba8 as i32,
                    loop_count: 0,
                    background_color_argb: 0,
                    params: Some(pb::EncodeParams {
                        lossless: true,
                        quality: None,
                        method: None,
                    }),
                    metadata: None,
                };
                let frames: Vec<(u32, Vec<u8>)> = (0..3).map(|_| (100, pixels.clone())).collect();
                encode_animation(&mut client, options, &frames).await.map(|_| ())
            } else if op.starts_with("decode") {
                decode(&mut client, &input, decode_options(&input)).await.map(|_| ())
            } else {
                let p = pb::EncodeParams {
                    lossless,
                    quality: None,
                    method: None,
                };
                encode(&mut client, &pixels, side, side, p).await.map(|_| ())
            }
        }));
    }

    // then
    for task in tasks {
        if let Err(status) = task.await.unwrap() {
            panic!("{op} {side}x{side} failed: {:?} {}", status.code(), status.message());
        }
    }
}
