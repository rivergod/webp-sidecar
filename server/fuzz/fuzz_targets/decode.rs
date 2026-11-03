//! Decode와 DecodeAnimation 경로(service.rs `decode`, `stream_animation`): 헤더를 읽고 픽셀 상한을 확인한 뒤
//! 정지 이미지는 RGBA로 디코딩하고, 애니메이션은 컨테이너 정보를 읽고 모든 프레임을 합성한다.
#![no_main]

use libfuzzer_sys::fuzz_target;
use webp_sidecar::webp;

/// 퍼징 속도와 메모리(libFuzzer 기본 RSS 한도 2 GiB)를 위해 서버 기본값(64 MP, 1000프레임)보다 낮춘다.
/// 상한 검사 자체는 같은 코드 경로다. 프레임 수 × 캔버스 크기에 따른 출력량은 별도 과제다(로드맵 5-1 출력량 폭탄).
const MAX_PIXELS: u64 = 4096 * 4096;
const MAX_FRAMES: u32 = 64;

fuzz_target!(|data: &[u8]| {
    let Ok(features) = webp::features(data) else {
        return;
    };
    if features.pixel_count() > MAX_PIXELS {
        return;
    }
    if !features.has_animation {
        let _ = webp::decode_still_rgba(data, &features);
        return;
    }
    if webp::container_info(data, MAX_FRAMES).is_err() {
        return;
    }
    let Ok(mut decoder) = webp::AnimDecoder::new(data) else {
        return;
    };
    for _ in 0..MAX_FRAMES {
        match decoder.next_frame() {
            Ok(Some((canvas, _timestamp))) => {
                std::hint::black_box(canvas);
            }
            _ => break,
        }
    }
});
