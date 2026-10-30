//! EncodeAnimation 경로(service.rs `encode_animation`): 캔버스·반복·배경색·품질 옵션 → 프레임마다 표시 시간과 함께
//! 더하기 → 마무리 → ICC/EXIF/XMP 넣기. 표시 시간은 아무 u32(누적 넘침 포함), 반복 횟수와 배경색도 아무 u32다.
//!
//! 충돌 검사에 더해, 만든 파일을 다시 읽어 프레임 수가 넣은 수 이하이고(libwebp는 같은 프레임을 합친다) 모든 프레임이
//! 합성되는지 확인한다.
//!
//! 입력 배치: [너비, 높이, 프레임 수, 플래그, quality 4바이트, 반복 4바이트, 배경색 4바이트, XMP 길이,
//! 표시 시간 4바이트 × 프레임 수, XMP…, 픽셀…].
#![no_main]

use libfuzzer_sys::fuzz_target;
use webp_imageio_server::webp;

/// 퍼징 속도를 위해 작게 둔다. 크기 검사는 같은 함수다.
const MAX_SIDE: u32 = 32;
const MAX_FRAMES: usize = 8;

fn u32_at(b: &[u8], i: usize) -> u32 {
    u32::from_le_bytes([b[i], b[i + 1], b[i + 2], b[i + 3]])
}

fuzz_target!(|data: &[u8]| {
    const HEAD: usize = 17;
    if data.len() < HEAD {
        return;
    }
    let (width, height) = (1 + u32::from(data[0]) % MAX_SIDE, 1 + u32::from(data[1]) % MAX_SIDE);
    let frames = 1 + usize::from(data[2]) % MAX_FRAMES;
    let flags = data[3];
    let params = webp::EncodeParams {
        lossless: flags & 1 != 0,
        quality: (flags & 2 != 0).then(|| f32::from_bits(u32_at(data, 4))),
        method: None,
    };
    let (loop_count, background) = (u32_at(data, 8), u32_at(data, 12));
    let xmp_len = usize::from(data[16]);
    let rest = &data[HEAD..];
    if rest.len() < frames * 4 {
        return;
    }
    let durations: Vec<u32> = (0..frames).map(|i| u32_at(rest, i * 4)).collect();
    let rest = &rest[frames * 4..];
    let (xmp, rest) = rest.split_at(xmp_len.min(rest.len()));
    let metadata = webp::MetadataChunks {
        xmp: (flags & 4 != 0).then(|| xmp.to_vec()),
        ..Default::default()
    };
    let frame_len = (width * height) as usize * webp::BYTES_PER_PIXEL;
    let source: Vec<u8> = if rest.is_empty() { vec![0x80] } else { rest.to_vec() };

    // 서버와 같은 순서: 인코더 생성(옵션 검사 포함) → 프레임 → 마무리 → 메타데이터.
    let Ok(mut encoder) = webp::AnimEncoder::new(width, height, loop_count, background, &params) else {
        return;
    };
    let mut offset = 0usize;
    for duration in &durations {
        // 프레임마다 다른 내용이 되도록 원본 바이트를 밀어 가며 채운다.
        let frame: Vec<u8> = source.iter().copied().cycle().skip(offset).take(frame_len).collect();
        offset = offset.wrapping_add(7);
        if encoder.add_frame(&frame, *duration).is_err() {
            return;
        }
    }
    let Ok(encoded) = encoder.finish() else {
        return;
    };
    let Ok(file) = webp::add_metadata(encoded, &metadata) else {
        return;
    };

    // 다시 읽기: 컨테이너 정보와 모든 프레임 합성.
    let info = webp::container_info(&file, MAX_FRAMES as u32).expect("encoded animation must demux");
    assert!(!info.frames.is_empty() && info.frames.len() <= frames, "{} frames from {frames}", info.frames.len());
    let mut decoder = webp::AnimDecoder::new(&file).expect("encoded animation must open");
    let mut count = 0;
    while let Some((canvas, _)) = decoder.next_frame().expect("frame must decode") {
        assert_eq!(canvas.len(), frame_len);
        count += 1;
    }
    assert_eq!(count, info.frames.len());
});
