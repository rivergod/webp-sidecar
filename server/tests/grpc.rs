//! gRPC 통합 테스트(정지 이미지, 상한, 프로토콜 규칙, 악성 입력). 실제 서버를 UDS로 띄워 호출한다.

mod common;

use bytes::Bytes;
use common::{TestServer, decode, decode_options, decode_raw, encode, get_features, pattern, psnr_rgb};
use tonic::Code;
use webp_sidecar::config::Limits;
use webp_sidecar::proto as pb;

const STILL: &[u8] = include_bytes!("../../spikes/testdata/still.webp");
const ANIM: &[u8] = include_bytes!("../../spikes/testdata/anim.webp");
const BOMB: &[u8] = include_bytes!("../../spikes/testdata/bomb_16384.webp");

/// 품질 90 lossy 왕복(인코딩 → 디코딩)의 최소 PSNR(RGB 채널). 단위: dB.
/// 근거: `common::pattern(512, 512, false)`, libwebp 1.6.0, method 기본값(4)에서 측정한 값은 41.0 dB다
/// (2026-10-23, 결과 파일 56,026바이트). libwebp는 결정적이므로 1 dB 여유만 둔다. 인코더 설정이 바뀌어
/// 화질이 떨어지면 잡는다.
const MIN_PSNR_DB_LOSSY_Q90_ROUNDTRIP: f64 = 40.0;

fn lossless() -> pb::EncodeParams {
    pb::EncodeParams {
        lossless: true,
        quality: None,
        method: None,
    }
}

#[tokio::test]
async fn get_info_reports_libwebp_version_and_limits() {
    // given
    let server = TestServer::start().await;
    let mut client = server.client().await;

    // when
    let info = client.get_info(pb::GetInfoRequest {}).await.unwrap().into_inner();

    // then
    let v = info.libwebp.unwrap();
    assert_eq!(
        (v.decoder.as_str(), v.encoder.as_str(), v.demux.as_str(), v.mux.as_str()),
        ("1.6.0", "1.6.0", "1.6.0", "1.6.0")
    );
    assert_eq!(info.limits.unwrap().max_pixels, Limits::default().max_pixels);
    assert_eq!(info.pixel_formats, vec![pb::PixelFormat::Rgba8 as i32]);
}

#[tokio::test]
async fn health_check_reports_serving() {
    // given
    let server = TestServer::start().await;
    let mut health = tonic_health::pb::health_client::HealthClient::new(server.channel().await);

    // when
    let response = health
        .check(tonic_health::pb::HealthCheckRequest {
            service: "webp.v1.WebPService".into(),
        })
        .await
        .unwrap()
        .into_inner();

    // then
    assert_eq!(
        response.status,
        tonic_health::pb::health_check_response::ServingStatus::Serving as i32
    );
}

#[tokio::test]
async fn decode_lossless_still_returns_rgba8() {
    // given
    let server = TestServer::start().await;
    let mut client = server.client().await;

    // when
    let (header, pixels) = decode(&mut client, STILL, decode_options(STILL)).await.unwrap();

    // then
    assert_eq!((header.width, header.height, header.has_alpha), (4, 3, true));
    assert_eq!(header.pixel_format, pb::PixelFormat::Rgba8 as i32);
    assert_eq!(&pixels[..4], &[0x12, 0x34, 0x56, 0x78]);
}

#[tokio::test]
async fn encode_decode_4k_lossless_round_trips_through_chunks() {
    // given: 3840x2160 RGBA = 33,177,600바이트. gRPC 기본 한도 4MB를 넘어 여러 조각으로 오가야 한다.
    let server = TestServer::start().await;
    let mut client = server.client().await;
    let pixels = pattern(3840, 2160, true);
    let webp = encode(&mut client, &pixels, 3840, 2160, lossless()).await.unwrap();

    // when
    let (header, decoded) = decode(&mut client, &webp, decode_options(&webp)).await.unwrap();

    // then
    assert_eq!((header.width, header.height), (3840, 2160));
    assert!(decoded == pixels, "lossless round trip must be pixel-exact");
}

#[tokio::test]
async fn encode_decode_lossy_q90_stays_above_psnr_threshold() {
    // given
    let server = TestServer::start().await;
    let mut client = server.client().await;
    let pixels = pattern(512, 512, false);
    let params = pb::EncodeParams {
        lossless: false,
        quality: Some(90.0),
        method: None,
    };
    let webp = encode(&mut client, &pixels, 512, 512, params).await.unwrap();

    // when
    let (_, decoded) = decode(&mut client, &webp, decode_options(&webp)).await.unwrap();

    // then
    let psnr = psnr_rgb(&pixels, &decoded);
    assert!(
        psnr >= MIN_PSNR_DB_LOSSY_Q90_ROUNDTRIP,
        "PSNR {psnr:.2} dB < {MIN_PSNR_DB_LOSSY_Q90_ROUNDTRIP} dB"
    );
}

#[tokio::test]
async fn decode_huge_declared_canvas_is_rejected_before_allocation() {
    // given: 41바이트 파일이 16384x16384를 선언한다(디코딩하면 1 GiB).
    let server = TestServer::start().await;
    let mut client = server.client().await;

    // when
    let status = decode(&mut client, BOMB, decode_options(BOMB)).await.unwrap_err();

    // then
    assert_eq!(status.code(), Code::ResourceExhausted, "{status:?}");
}

#[tokio::test]
async fn decode_client_max_pixels_stricter_than_server_is_enforced() {
    // given
    let server = TestServer::start().await;
    let mut client = server.client().await;
    let options = pb::DecodeOptions {
        max_pixels: Some(11),
        ..decode_options(STILL)
    };

    // when
    let status = decode(&mut client, STILL, options).await.unwrap_err();

    // then
    assert_eq!(status.code(), Code::ResourceExhausted, "{status:?}");
}

#[tokio::test]
async fn decode_garbage_is_invalid_argument() {
    // given
    let server = TestServer::start().await;
    let mut client = server.client().await;
    let garbage = b"RIFF\x10\x00\x00\x00WEBPVP8 \x04\x00\x00\x00abcd".to_vec();

    // when
    let status = decode(&mut client, &garbage, decode_options(&garbage))
        .await
        .unwrap_err();

    // then
    assert_eq!(status.code(), Code::InvalidArgument, "{status:?}");
}

#[tokio::test]
async fn decode_truncated_file_is_invalid_argument_and_server_keeps_serving() {
    // given
    let server = TestServer::start().await;
    let mut client = server.client().await;
    let truncated = &STILL[..STILL.len() / 2];

    // when
    let status = decode(&mut client, truncated, decode_options(truncated))
        .await
        .unwrap_err();

    // then
    assert_eq!(status.code(), Code::InvalidArgument, "{status:?}");
    assert!(
        decode(&mut client, STILL, decode_options(STILL)).await.is_ok(),
        "server must keep serving"
    );
}

#[tokio::test]
async fn decode_frame_index_beyond_still_is_out_of_range() {
    // given
    let server = TestServer::start().await;
    let mut client = server.client().await;
    let options = pb::DecodeOptions {
        frame_index: 1,
        ..decode_options(STILL)
    };

    // when
    let status = decode(&mut client, STILL, options).await.unwrap_err();

    // then
    assert_eq!(status.code(), Code::OutOfRange, "{status:?}");
}

#[tokio::test]
async fn decode_first_message_is_data_is_invalid_argument() {
    // given
    let server = TestServer::start().await;
    let mut client = server.client().await;
    let messages = vec![pb::DecodeRequest {
        payload: Some(pb::decode_request::Payload::WebpData(Bytes::from_static(STILL))),
    }];

    // when
    let status = decode_raw(&mut client, messages).await.unwrap_err();

    // then
    assert_eq!(status.code(), Code::InvalidArgument, "{status:?}");
}

#[tokio::test]
async fn decode_declared_size_mismatch_is_invalid_argument() {
    // given
    let server = TestServer::start().await;
    let mut client = server.client().await;
    let options = pb::DecodeOptions {
        input_bytes: STILL.len() as u64 + 1,
        ..decode_options(STILL)
    };

    // when
    let status = decode(&mut client, STILL, options).await.unwrap_err();

    // then
    assert_eq!(status.code(), Code::InvalidArgument, "{status:?}");
}

#[tokio::test]
async fn decode_chunk_larger_than_max_chunk_bytes_is_invalid_argument() {
    // given
    let limits = Limits {
        max_chunk_bytes: 64,
        ..Limits::default()
    };
    let server = TestServer::start_with(limits, 1).await;
    let mut client = server.client().await;

    // when: 198바이트 파일을 한 조각으로 보낸다.
    let status = decode(&mut client, STILL, decode_options(STILL)).await.unwrap_err();

    // then
    assert_eq!(status.code(), Code::InvalidArgument, "{status:?}");
}

#[tokio::test]
async fn decode_input_over_max_input_bytes_is_resource_exhausted() {
    // given
    let limits = Limits {
        max_input_bytes: 100,
        ..Limits::default()
    };
    let server = TestServer::start_with(limits, 1).await;
    let mut client = server.client().await;

    // when
    let status = decode(&mut client, STILL, decode_options(STILL)).await.unwrap_err();

    // then
    assert_eq!(status.code(), Code::ResourceExhausted, "{status:?}");
}

#[tokio::test]
async fn encode_over_max_pixels_is_resource_exhausted() {
    // given
    let limits = Limits {
        max_pixels: 100,
        ..Limits::default()
    };
    let server = TestServer::start_with(limits, 1).await;
    let mut client = server.client().await;

    // when
    let status = encode(&mut client, &pattern(11, 10, false), 11, 10, lossless())
        .await
        .unwrap_err();

    // then
    assert_eq!(status.code(), Code::ResourceExhausted, "{status:?}");
}

#[tokio::test]
async fn encode_zero_width_is_invalid_argument() {
    // given
    let server = TestServer::start().await;
    let mut client = server.client().await;

    // when
    let status = encode(&mut client, &[], 0, 10, lossless()).await.unwrap_err();

    // then
    assert_eq!(status.code(), Code::InvalidArgument, "{status:?}");
}

#[tokio::test]
async fn encode_quality_out_of_range_is_invalid_argument() {
    // given
    let server = TestServer::start().await;
    let mut client = server.client().await;
    let params = pb::EncodeParams {
        lossless: false,
        quality: Some(150.0),
        method: None,
    };

    // when
    let status = encode(&mut client, &pattern(2, 2, false), 2, 2, params)
        .await
        .unwrap_err();

    // then
    assert_eq!(status.code(), Code::InvalidArgument, "{status:?}");
}

#[tokio::test]
async fn get_features_still_with_metadata_returns_payloads() {
    // given
    let server = TestServer::start().await;
    let mut client = server.client().await;

    // when
    let response = get_features(&mut client, STILL, true).await.unwrap();

    // then
    let f = response.features.unwrap();
    assert_eq!((f.width, f.height, f.has_animation, f.frame_count), (4, 3, false, 1));
    assert_eq!(f.compression, pb::Compression::Lossless as i32);
    let m = response.metadata.unwrap();
    assert_eq!(m.icc_profile.map(|b| b.len()), Some(28));
    assert_eq!(m.exif.map(|b| b.len()), Some(16));
    assert_eq!(m.xmp.map(|b| b.len()), Some(37));
}

#[tokio::test]
async fn get_features_without_metadata_flag_omits_metadata() {
    // given
    let server = TestServer::start().await;
    let mut client = server.client().await;

    // when
    let response = get_features(&mut client, STILL, false).await.unwrap();

    // then
    assert!(response.metadata.is_none());
}

#[tokio::test]
async fn get_features_animation_reports_frames_and_loop() {
    // given
    let server = TestServer::start().await;
    let mut client = server.client().await;

    // when
    let response = get_features(&mut client, ANIM, false).await.unwrap();

    // then
    let f = response.features.unwrap();
    assert!(f.has_animation);
    assert_eq!(f.frame_count, 3);
    let anim = f.animation.unwrap();
    assert_eq!(anim.frames.len(), 3);
    assert!(
        anim.frames
            .iter()
            .all(|fr| fr.duration_ms == 100 && fr.width == 4 && fr.height == 3)
    );
}

#[tokio::test]
async fn get_features_header_only_file_is_invalid_argument() {
    // given: 헤더만 있고 비트스트림이 없는 파일. demux는 완전한 파일만 받는다.
    let server = TestServer::start().await;
    let mut client = server.client().await;

    // when
    let status = get_features(&mut client, BOMB, false).await.unwrap_err();

    // then
    assert_eq!(status.code(), Code::InvalidArgument, "{status:?}");
}
