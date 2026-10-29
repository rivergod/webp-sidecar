//! 요청당 작업량 상한(ADR-0017): 합성 픽셀 수 상한, 대기 상한, 요청 시간 상한.

mod common;

use bytes::Bytes;
use common::{TestServer, decode, encode_animation, pattern};
use std::path::PathBuf;
use std::time::{Duration, Instant};
use tokio::sync::mpsc;
use tokio_stream::StreamExt;
use tokio_stream::wrappers::ReceiverStream;
use tonic::Code;
use webp_imageio_server::admission::RequestPolicy;
use webp_imageio_server::config::Limits;
use webp_imageio_server::proto as pb;

fn sample(rel: &str) -> Vec<u8> {
    std::fs::read(PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../samples").join(rel)).unwrap()
}

fn policy(max_queued: usize, max_request_ms: u64) -> RequestPolicy {
    RequestPolicy {
        max_queued_requests: max_queued,
        max_request: Duration::from_millis(max_request_ms),
    }
}

fn anim_options(width: u32, height: u32) -> pb::EncodeAnimationOptions {
    pb::EncodeAnimationOptions {
        canvas_width: width,
        canvas_height: height,
        pixel_format: pb::PixelFormat::Rgba8 as i32,
        loop_count: 0,
        background_color_argb: 0,
        params: Some(pb::EncodeParams {
            lossless: false,
            quality: Some(75.0),
            method: None,
        }),
        metadata: None,
    }
}

/// 크기는 같고 내용이 다른 프레임들(같은 프레임은 인코더가 합친다).
fn distinct_frames(width: u32, height: u32, n: usize) -> Vec<(u32, Vec<u8>)> {
    (0..n)
        .map(|i| {
            let mut px = pattern(width, height, false);
            let offset = i * 4 * width as usize * 16;
            for b in &mut px[offset..offset + 4 * width as usize * 8] {
                *b = 255;
            }
            (100, px)
        })
        .collect()
}

/// options와 픽셀 일부만 보내고 멈춘 Encode 업로드. 반환한 송신기를 버리기 전까지 슬롯을 쥔다.
async fn stalled_encode(
    server: &TestServer,
) -> (
    mpsc::Sender<pb::EncodeRequest>,
    tokio::task::JoinHandle<Result<(), tonic::Status>>,
) {
    use pb::encode_request::Payload;
    let mut client = server.client().await;
    let (tx, rx) = mpsc::channel(4);
    tx.send(pb::EncodeRequest {
        payload: Some(Payload::Options(pb::EncodeOptions {
            width: 64,
            height: 64,
            pixel_format: pb::PixelFormat::Rgba8 as i32,
            params: None,
            metadata: None,
        })),
    })
    .await
    .unwrap();
    tx.send(pb::EncodeRequest {
        payload: Some(Payload::Pixels(Bytes::from_static(&[0u8; 100]))),
    })
    .await
    .unwrap();
    let call = tokio::spawn(async move {
        let mut stream = client.encode(ReceiverStream::new(rx)).await?.into_inner();
        while let Some(m) = stream.next().await {
            m?;
        }
        Ok(())
    });
    (tx, call)
}

#[tokio::test]
async fn output_bomb_decode_animation_and_last_frame_are_rejected_before_decoding() {
    // Arrange: 4096² 캔버스 × 1000프레임(합성 출력 약 64 GiB)
    let server = TestServer::start().await;
    let mut client = server.client().await;
    let bomb = sample("malformed/anim_frames_1000_canvas_4096.webp");
    let mut last = common::decode_options(&bomb);
    last.frame_index = 999;

    // Act
    let started = Instant::now();
    let animation = common::decode_animation(&mut client, &bomb).await;
    let frame = common::decode_raw(
        &mut client,
        vec![
            pb::DecodeRequest {
                payload: Some(pb::decode_request::Payload::Options(last)),
            },
            pb::DecodeRequest {
                payload: Some(pb::decode_request::Payload::WebpData(Bytes::from(bomb.clone()))),
            },
        ],
    )
    .await;
    let elapsed = started.elapsed();

    // Assert
    assert_eq!(animation.unwrap_err().code(), Code::ResourceExhausted);
    assert_eq!(frame.unwrap_err().code(), Code::ResourceExhausted);
    assert!(elapsed < Duration::from_secs(2), "rejection took {elapsed:?}");
}

#[tokio::test]
async fn encode_animation_over_composited_limit_is_resource_exhausted() {
    // Arrange: 합성 상한 = 캔버스 2장 분량
    let limits = Limits {
        max_composited_pixels: 64 * 64 * 2,
        ..Limits::default()
    };
    let server = TestServer::start_with(limits, 1).await;
    let mut client = server.client().await;
    let frames = distinct_frames(64, 64, 3);

    // Act
    let result = encode_animation(&mut client, anim_options(64, 64), &frames).await;

    // Assert
    assert_eq!(result.unwrap_err().code(), Code::ResourceExhausted);
}

#[tokio::test]
async fn request_beyond_queue_limit_is_unavailable_immediately() {
    // Arrange: 슬롯 1, 대기 상한 1. 멈춘 업로드가 슬롯을 쥐고, 디코딩 하나가 대기한다.
    let server = TestServer::start_with_policy(Limits::default(), 1, policy(1, 60_000)).await;
    let (holder, held) = stalled_encode(&server).await;
    tokio::time::sleep(Duration::from_millis(200)).await;
    let still = sample("generated/still/lossy_17x9.webp");
    let queued = {
        let mut client = server.client().await;
        let data = still.clone();
        tokio::spawn(async move {
            decode(&mut client, &data, common::decode_options(&data))
                .await
                .map(|_| ())
        })
    };
    tokio::time::sleep(Duration::from_millis(200)).await;
    let mut client = server.client().await;

    // Act
    let started = Instant::now();
    let rejected = decode(&mut client, &still, common::decode_options(&still)).await;
    let elapsed = started.elapsed();

    // Assert: 즉시 거부되고, 슬롯이 풀리면 대기하던 요청은 처리된다.
    let status = rejected.unwrap_err();
    assert_eq!(status.code(), Code::Unavailable);
    assert!(status.message().contains("server busy"), "{}", status.message());
    assert!(elapsed < Duration::from_secs(1), "rejection took {elapsed:?}");
    drop(holder);
    assert_eq!(held.await.unwrap().unwrap_err().code(), Code::InvalidArgument);
    assert!(queued.await.unwrap().is_ok());
}

#[tokio::test]
async fn stalled_upload_is_ended_by_request_time_limit_and_frees_slot() {
    // Arrange: 슬롯 1, 시간 상한 300 ms
    let server = TestServer::start_with_policy(Limits::default(), 1, policy(8, 300)).await;
    let (_holder, held) = stalled_encode(&server).await;
    let still = sample("generated/still/lossy_17x9.webp");
    let mut client = server.client().await;

    // Act
    let started = Instant::now();
    let next = decode(&mut client, &still, common::decode_options(&still)).await;
    let elapsed = started.elapsed();

    // Assert
    assert_eq!(held.await.unwrap().unwrap_err().code(), Code::DeadlineExceeded);
    assert!(next.is_ok());
    assert!(elapsed < Duration::from_secs(3), "slot freed after {elapsed:?}");
}

#[tokio::test]
async fn client_not_reading_response_is_ended_by_request_time_limit_and_frees_slot() {
    // Arrange: 큰 애니메이션(1024² × 6프레임, 합성 출력 24 MiB)을 만들어 두고, 슬롯 1·시간 상한 500 ms 서버에서
    // DecodeAnimation 응답을 읽지 않는다.
    let maker = TestServer::start().await;
    let animation = encode_animation(
        &mut maker.client().await,
        anim_options(1024, 1024),
        &distinct_frames(1024, 1024, 6),
    )
    .await
    .unwrap();
    let server = TestServer::start_with_policy(Limits::default(), 1, policy(8, 500)).await;
    let mut reader = server.client().await;
    let request = vec![
        pb::DecodeAnimationRequest {
            payload: Some(pb::decode_animation_request::Payload::Options(
                pb::DecodeAnimationOptions {
                    input_bytes: animation.len() as u64,
                    pixel_format: pb::PixelFormat::Rgba8 as i32,
                    ..Default::default()
                },
            )),
        },
        pb::DecodeAnimationRequest {
            payload: Some(pb::decode_animation_request::Payload::WebpData(Bytes::from(animation))),
        },
    ];
    let mut unread = reader
        .decode_animation(tokio_stream::iter(request))
        .await
        .unwrap()
        .into_inner();
    let still = sample("generated/still/lossy_17x9.webp");
    let mut client = server.client().await;

    // Act
    let started = Instant::now();
    let next = decode(&mut client, &still, common::decode_options(&still)).await;
    let elapsed = started.elapsed();

    // Assert: 다른 요청이 처리되고, 읽지 않던 스트림은 끝까지 받지 못하고 DEADLINE_EXCEEDED로 끝난다.
    assert!(next.is_ok());
    assert!(elapsed < Duration::from_secs(3), "slot freed after {elapsed:?}");
    let mut ended_by_deadline = false;
    while let Some(message) = unread.next().await {
        if let Err(status) = message {
            ended_by_deadline = status.code() == Code::DeadlineExceeded;
            break;
        }
    }
    assert!(ended_by_deadline, "the unread stream must end with DEADLINE_EXCEEDED");
}
