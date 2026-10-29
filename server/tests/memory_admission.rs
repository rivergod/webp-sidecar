//! 메모리 예산 기반 요청 허용(ADR-0016): 예산 초과 거부, 메모리 대기, 프레임 중간 예약의 교착 없음, 헤더 먼저 읽기.
//!
//! 예산 계산은 서버 추정식을 따른다: 정지 이미지 인코딩 40 B/px, 애니메이션 인코딩 캔버스 60 B/px + lossy 프레임마다
//! 0.25 B/px, 정지 이미지 디코딩 입력 + 6 B/px(server/src/service.rs).

mod common;

use bytes::Bytes;
use common::{TestServer, decode, encode, pattern};
use std::path::PathBuf;
use std::time::{Duration, Instant};
use tokio::sync::mpsc;
use tokio_stream::StreamExt;
use tokio_stream::wrappers::ReceiverStream;
use tonic::Code;
use webp_imageio_server::proto as pb;

const KIB: u64 = 1024;
const MIB: u64 = 1024 * KIB;

/// 거부가 "기다리지 않음"으로 볼 수 있는 시간. 디버그 빌드와 CI 여유를 둔 값이다.
const PROMPT: Duration = Duration::from_secs(2);

fn sample(rel: &str) -> Vec<u8> {
    std::fs::read(PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../samples").join(rel)).unwrap()
}

fn lossy() -> pb::EncodeParams {
    pb::EncodeParams {
        lossless: false,
        quality: Some(75.0),
        method: None,
    }
}

/// options와 픽셀 일부만 보내고 멈춘 Encode. 추정 메모리(40 B × 픽셀)와 슬롯을 쥔다. 송신기를 버리면 업로드가 모자란
/// 채 끝나 `INVALID_ARGUMENT`로 끝난다.
async fn held_encode(
    server: &TestServer,
    width: u32,
    height: u32,
) -> (
    mpsc::Sender<pb::EncodeRequest>,
    tokio::task::JoinHandle<Result<(), tonic::Status>>,
) {
    use pb::encode_request::Payload;
    let mut client = server.client().await;
    let (tx, rx) = mpsc::channel(4);
    tx.send(pb::EncodeRequest {
        payload: Some(Payload::Options(pb::EncodeOptions {
            width,
            height,
            pixel_format: pb::PixelFormat::Rgba8 as i32,
            params: Some(lossy()),
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

/// 요청 스트림을 송신기로 조절하는 EncodeAnimation. 응답(결과 파일 크기)은 반환한 작업에서 받는다.
async fn open_encode_animation(
    server: &TestServer,
    width: u32,
    height: u32,
) -> (
    mpsc::Sender<pb::EncodeAnimationRequest>,
    tokio::task::JoinHandle<Result<usize, tonic::Status>>,
) {
    use pb::encode_animation_request::Payload;
    let mut client = server.client().await;
    let (tx, rx) = mpsc::channel(8);
    tx.send(pb::EncodeAnimationRequest {
        payload: Some(Payload::Options(pb::EncodeAnimationOptions {
            canvas_width: width,
            canvas_height: height,
            pixel_format: pb::PixelFormat::Rgba8 as i32,
            loop_count: 0,
            background_color_argb: 0,
            params: Some(lossy()),
            metadata: None,
        })),
    })
    .await
    .unwrap();
    let call = tokio::spawn(async move {
        let mut stream = client.encode_animation(ReceiverStream::new(rx)).await?.into_inner();
        let mut bytes = 0;
        while let Some(m) = stream.next().await {
            if let Some(pb::encode_animation_response::Payload::WebpData(d)) = m?.payload {
                bytes += d.len();
            }
        }
        Ok(bytes)
    });
    (tx, call)
}

/// 프레임 `n`개를 보낸다. 서버가 요청을 먼저 끝내 보낼 수 없게 되면 멈춘다.
async fn send_frames(tx: &mpsc::Sender<pb::EncodeAnimationRequest>, rgba: &[u8], n: usize) {
    use pb::encode_animation_request::Payload;
    for _ in 0..n {
        let frame = pb::EncodeAnimationRequest {
            payload: Some(Payload::Frame(pb::EncodeFrame { duration_ms: 100 })),
        };
        let pixels = pb::EncodeAnimationRequest {
            payload: Some(Payload::Pixels(Bytes::copy_from_slice(rgba))),
        };
        if tx.send(frame).await.is_err() || tx.send(pixels).await.is_err() {
            return;
        }
    }
}

#[tokio::test]
async fn encode_over_budget_is_resource_exhausted_without_waiting() {
    // Arrange: 예산 8 MiB, 512² 인코딩 추정 10 MiB
    let server = TestServer::start_with_budget(2, 8 * MIB).await;
    let mut client = server.client().await;
    let pixels = pattern(512, 512, false);

    // Act
    let started = Instant::now();
    let status = encode(&mut client, &pixels, 512, 512, lossy()).await.unwrap_err();

    // Assert
    assert_eq!(status.code(), Code::ResourceExhausted);
    assert!(status.message().contains("memory budget"), "{}", status.message());
    assert!(started.elapsed() < PROMPT, "rejection took {:?}", started.elapsed());
}

#[tokio::test]
async fn decode_over_budget_is_resource_exhausted_from_the_header() {
    // Arrange: 3840×2160 정지 이미지 디코딩 추정 = 입력 + 6 B × 829만 픽셀(약 48 MiB) > 예산 32 MiB
    let server = TestServer::start_with_budget(2, 32 * MIB).await;
    let mut client = server.client().await;
    let image = sample("generated/still/lossless_3840x2160.webp");

    // Act
    let status = decode(&mut client, &image, common::decode_options(&image))
        .await
        .unwrap_err();

    // Assert
    assert_eq!(status.code(), Code::ResourceExhausted);
    assert!(status.message().contains("estimated memory"), "{}", status.message());
}

#[tokio::test]
async fn request_without_free_budget_waits_until_memory_is_released() {
    // Arrange: 예산 8 MiB, 360² 인코딩 추정 약 4.9 MiB라 둘은 함께 못 든다. 슬롯은 2개로 남는다
    let server = TestServer::start_with_budget(2, 8 * MIB).await;
    let (holder, held) = held_encode(&server, 360, 360).await;
    tokio::time::sleep(Duration::from_millis(200)).await;
    let mut client = server.client().await;
    let waiting = tokio::spawn(async move {
        let pixels = pattern(360, 360, false);
        encode(&mut client, &pixels, 360, 360, lossy()).await
    });
    tokio::time::sleep(Duration::from_millis(300)).await;
    assert!(!waiting.is_finished(), "second request must wait for memory");

    // Act: 첫 요청이 끝나 예약을 놓는다
    drop(holder);
    let result = waiting.await.unwrap();

    // Assert
    assert_eq!(held.await.unwrap().unwrap_err().code(), Code::InvalidArgument);
    assert!(result.is_ok(), "{result:?}");
}

#[tokio::test]
async fn encode_animation_frames_beyond_budget_end_without_deadlock() {
    // Arrange: 256² 캔버스 몫 3840 KiB × 2 + lossy 프레임 몫(16 KiB) 4개만큼의 예산. A가 캔버스 몫을 쥔 채 멈춘다
    let server = TestServer::start_with_budget(2, (2 * 3840 + 4 * 16) * KIB).await;
    let frame = pattern(256, 256, false);
    let (a_tx, a_call) = open_encode_animation(&server, 256, 256).await;
    tokio::time::sleep(Duration::from_millis(200)).await;
    let (b_tx, b_call) = open_encode_animation(&server, 256, 256).await;

    // Act: B가 프레임 10개를 보낸다. 5번째 프레임 몫부터 예산이 모자라다
    let started = Instant::now();
    send_frames(&b_tx, &frame, 10).await;
    drop(b_tx);
    let b = b_call.await.unwrap();
    let b_elapsed = started.elapsed();
    send_frames(&a_tx, &frame, 10).await;
    drop(a_tx);
    let a = a_call.await.unwrap();

    // Assert: B는 기다리지 않고 끝나고, B가 놓은 예산으로 A가 끝난다
    let b = b.unwrap_err();
    assert_eq!(b.code(), Code::ResourceExhausted);
    assert!(b.message().contains("memory budget exhausted"), "{}", b.message());
    assert!(b_elapsed < PROMPT * 5, "B ended after {b_elapsed:?}");
    assert!(a.unwrap() > 0);
}

#[tokio::test]
async fn decode_with_tiny_header_chunks_succeeds() {
    // Arrange: 머리(64바이트)를 7바이트 조각 여러 개로 보낸다
    use pb::decode_request::Payload;
    let server = TestServer::start().await;
    let mut client = server.client().await;
    let image = sample("generated/still/lossless_alpha_17x9.webp");
    let expected = decode(&mut client, &image, common::decode_options(&image))
        .await
        .unwrap();
    let mut messages = vec![pb::DecodeRequest {
        payload: Some(Payload::Options(common::decode_options(&image))),
    }];
    messages.extend(image.chunks(7).map(|c| pb::DecodeRequest {
        payload: Some(Payload::WebpData(Bytes::copy_from_slice(c))),
    }));

    // Act
    let decoded = common::decode_raw(&mut client, messages).await;

    // Assert
    assert_eq!(decoded.unwrap(), expected);
}

#[tokio::test]
async fn canvas_over_max_pixels_is_rejected_before_the_rest_is_sent() {
    // Arrange: 16384² 선언 파일(41바이트)의 머리 64바이트만 보내고, 1 MiB를 선언한 업로드는 열어 둔다
    use pb::decode_request::Payload;
    let bomb =
        std::fs::read(PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../spikes/testdata/bomb_16384.webp")).unwrap();
    let mut head = bomb.clone();
    head.resize(64, 0);
    let server = TestServer::start().await;
    let mut client = server.client().await;
    let (tx, rx) = mpsc::channel(4);
    tx.send(pb::DecodeRequest {
        payload: Some(Payload::Options(pb::DecodeOptions {
            input_bytes: MIB,
            ..common::decode_options(&bomb)
        })),
    })
    .await
    .unwrap();
    tx.send(pb::DecodeRequest {
        payload: Some(Payload::WebpData(Bytes::from(head))),
    })
    .await
    .unwrap();

    // Act
    let started = Instant::now();
    let status = match client.decode(ReceiverStream::new(rx)).await {
        Ok(response) => response.into_inner().next().await.unwrap().unwrap_err(),
        Err(status) => status,
    };

    // Assert
    assert_eq!(status.code(), Code::ResourceExhausted);
    assert!(status.message().contains("max_pixels"), "{}", status.message());
    assert!(started.elapsed() < PROMPT, "rejection took {:?}", started.elapsed());
    drop(tx);
}
