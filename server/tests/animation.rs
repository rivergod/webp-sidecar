//! 애니메이션과 메타데이터 gRPC 통합 테스트(Phase 3). 실제 서버를 UDS로 띄워 호출한다.

mod common;

use bytes::Bytes;
use common::{
    TestServer, decode, decode_animation, decode_options, encode_animation, encode_animation_raw, encode_with_metadata,
    get_features, pattern,
};
use tonic::Code;
use webp_imageio_server::config::Limits;
use webp_imageio_server::proto as pb;

const ANIM: &[u8] = include_bytes!("../../spikes/testdata/anim.webp");

fn anim_options(width: u32, height: u32) -> pb::EncodeAnimationOptions {
    pb::EncodeAnimationOptions {
        canvas_width: width,
        canvas_height: height,
        pixel_format: pb::PixelFormat::Rgba8 as i32,
        loop_count: 3,
        background_color_argb: 0xFF10_2030,
        params: Some(pb::EncodeParams {
            lossless: true,
            quality: None,
            method: None,
        }),
        metadata: None,
    }
}

#[tokio::test]
async fn decode_animation_streams_every_composited_frame() {
    // given
    let server = TestServer::start().await;
    let mut client = server.client().await;

    // when
    let (features, frames) = decode_animation(&mut client, ANIM).await.unwrap();

    // then
    assert!(features.has_animation);
    assert_eq!(frames.len(), 3);
    let firsts: Vec<&[u8]> = frames.iter().map(|(_, px)| &px[..4]).collect();
    assert_eq!(
        firsts,
        vec![&[255, 0, 0, 255][..], &[0, 255, 0, 255][..], &[0, 0, 255, 128][..]]
    );
    assert!(
        frames
            .iter()
            .all(|(h, _)| h.duration_ms == 100 && h.width == 4 && h.height == 3)
    );
}

#[tokio::test]
async fn decode_frame_index_of_animation_returns_that_composited_frame() {
    // given
    let server = TestServer::start().await;
    let mut client = server.client().await;
    let options = pb::DecodeOptions {
        frame_index: 2,
        ..decode_options(ANIM)
    };

    // when
    let (header, pixels) = decode(&mut client, ANIM, options).await.unwrap();

    // then
    assert_eq!(header.frame_index, 2);
    assert_eq!(&pixels[..4], &[0, 0, 255, 128]);
}

#[tokio::test]
async fn decode_frame_index_beyond_animation_is_out_of_range() {
    // given
    let server = TestServer::start().await;
    let mut client = server.client().await;
    let options = pb::DecodeOptions {
        frame_index: 3,
        ..decode_options(ANIM)
    };

    // when
    let status = decode(&mut client, ANIM, options).await.unwrap_err();

    // then
    assert_eq!(status.code(), Code::OutOfRange, "{status:?}");
}

#[tokio::test]
async fn decode_animation_still_image_yields_single_frame() {
    // given
    let server = TestServer::start().await;
    let mut client = server.client().await;
    let still = include_bytes!("../../spikes/testdata/still.webp");

    // when
    let (features, frames) = decode_animation(&mut client, still).await.unwrap();

    // then
    assert!(!features.has_animation);
    assert_eq!(frames.len(), 1);
}

#[tokio::test]
async fn encode_animation_lossless_round_trips_frames_loop_background_and_durations() {
    // given
    let server = TestServer::start().await;
    let mut client = server.client().await;
    let frames: Vec<(u32, Vec<u8>)> = (0..4)
        .map(|i| (50 * (i + 1), pattern(64 + i, 48, true)))
        .map(|(d, _)| d)
        .zip((0..4).map(|i| {
            let mut p = pattern(64, 48, true);
            p.iter_mut().step_by(7).for_each(|b| *b = b.wrapping_add(i as u8 * 40));
            p
        }))
        .collect();

    // when
    let webp = encode_animation(&mut client, anim_options(64, 48), &frames)
        .await
        .unwrap();

    // then
    let (features, decoded) = decode_animation(&mut client, &webp).await.unwrap();
    let anim = features.animation.unwrap();
    assert_eq!((anim.loop_count, anim.background_color_argb), (3, 0xFF10_2030));
    assert_eq!(decoded.len(), frames.len());
    for ((duration, want), (header, got)) in frames.iter().zip(&decoded) {
        assert_eq!(header.duration_ms, *duration);
        assert!(got == want, "frame {} differs", header.frame_index);
    }
}

#[tokio::test]
async fn encode_animation_with_metadata_keeps_chunks() {
    // given
    let server = TestServer::start().await;
    let mut client = server.client().await;
    let metadata = pb::Metadata {
        icc_profile: Some(Bytes::from_static(b"fake-icc")),
        exif: Some(Bytes::from_static(b"II*\0exif")),
        xmp: Some(Bytes::from_static(b"<x:xmpmeta/>")),
    };
    let options = pb::EncodeAnimationOptions {
        metadata: Some(metadata.clone()),
        ..anim_options(8, 8)
    };
    let frames = vec![(100, pattern(8, 8, true)), (100, pattern(8, 8, false))];

    // when
    let webp = encode_animation(&mut client, options, &frames).await.unwrap();

    // then
    let got = get_features(&mut client, &webp, true).await.unwrap().metadata.unwrap();
    assert_eq!(got, metadata);
}

#[tokio::test]
async fn encode_still_with_metadata_keeps_chunks_and_pixels() {
    // given
    let server = TestServer::start().await;
    let mut client = server.client().await;
    let pixels = pattern(16, 9, true);
    let metadata = pb::Metadata {
        icc_profile: Some(Bytes::from_static(b"icc")),
        exif: None,
        xmp: Some(Bytes::from_static(b"<x/>")),
    };

    // when
    let webp = encode_with_metadata(&mut client, &pixels, 16, 9, metadata.clone())
        .await
        .unwrap();

    // then
    assert_eq!(
        get_features(&mut client, &webp, true).await.unwrap().metadata.unwrap(),
        metadata
    );
    assert!(decode(&mut client, &webp, decode_options(&webp)).await.unwrap().1 == pixels);
}

#[tokio::test]
async fn encode_with_metadata_over_limit_is_resource_exhausted() {
    // given
    let limits = Limits {
        max_metadata_bytes: 4,
        ..Limits::default()
    };
    let server = TestServer::start_with(limits, 1).await;
    let mut client = server.client().await;
    let metadata = pb::Metadata {
        icc_profile: Some(Bytes::from_static(b"12345")),
        exif: None,
        xmp: None,
    };

    // when
    let status = encode_with_metadata(&mut client, &pattern(2, 2, true), 2, 2, metadata)
        .await
        .unwrap_err();

    // then
    assert_eq!(status.code(), Code::ResourceExhausted, "{status:?}");
}

#[tokio::test]
async fn encode_animation_pixels_before_frame_is_invalid_argument() {
    // given
    use pb::encode_animation_request::Payload;
    let server = TestServer::start().await;
    let mut client = server.client().await;
    let messages = vec![
        pb::EncodeAnimationRequest {
            payload: Some(Payload::Options(anim_options(2, 2))),
        },
        pb::EncodeAnimationRequest {
            payload: Some(Payload::Pixels(Bytes::from(vec![0u8; 16]))),
        },
    ];

    // when
    let status = encode_animation_raw(&mut client, messages).await.unwrap_err();

    // then
    assert_eq!(status.code(), Code::InvalidArgument, "{status:?}");
}

#[tokio::test]
async fn encode_animation_short_frame_is_invalid_argument() {
    // given
    let server = TestServer::start().await;
    let mut client = server.client().await;
    let frames = vec![(100, vec![0u8; 15])];

    // when
    let status = encode_animation(&mut client, anim_options(2, 2), &frames)
        .await
        .unwrap_err();

    // then
    assert_eq!(status.code(), Code::InvalidArgument, "{status:?}");
}

#[tokio::test]
async fn encode_animation_more_than_max_frames_is_resource_exhausted() {
    // given
    let limits = Limits {
        max_frames: 2,
        ..Limits::default()
    };
    let server = TestServer::start_with(limits, 1).await;
    let mut client = server.client().await;
    let frames = vec![(10, vec![0u8; 16]); 3];

    // when
    let status = encode_animation(&mut client, anim_options(2, 2), &frames)
        .await
        .unwrap_err();

    // then
    assert_eq!(status.code(), Code::ResourceExhausted, "{status:?}");
}

#[tokio::test]
async fn encode_animation_no_frames_is_invalid_argument() {
    // given
    let server = TestServer::start().await;
    let mut client = server.client().await;

    // when
    let status = encode_animation(&mut client, anim_options(2, 2), &[])
        .await
        .unwrap_err();

    // then
    assert_eq!(status.code(), Code::InvalidArgument, "{status:?}");
}
