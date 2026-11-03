//! Encode 경로(service.rs `encode`): 크기·품질 옵션 검사 → libwebp 인코딩 → ICC/EXIF/XMP 넣기(WebPMux).
//! 클라이언트가 보내는 값을 그대로 흉내 낸다: quality는 아무 f32 비트(NaN, 무한대 포함), method는 아무 u32다.
//!
//! 충돌 검사에 더해 두 성질을 확인한다(실패하면 panic으로 보고된다).
//! - lossless 왕복은 픽셀이 바뀌지 않는다(ADR-0004, `config.exact = 1`).
//! - 넣은 메타데이터는 같은 바이트로 다시 읽힌다.
//!
//! 입력 배치: [너비, 높이, 플래그, quality 4바이트, method, ICC 길이, EXIF 길이, XMP 길이, 메타데이터…, 픽셀…].
#![no_main]

use libfuzzer_sys::fuzz_target;
use webp_sidecar::webp;

/// 퍼징 속도를 위해 작게 둔다. 크기 검사(1..=16383)는 같은 함수다.
const MAX_SIDE: u32 = 64;
const MAX_METADATA_BYTES: u64 = 2 * 1024 * 1024;

fuzz_target!(|data: &[u8]| {
    let Some((&[w, h, flags, q0, q1, q2, q3, method, l_icc, l_exif, l_xmp], rest)) = data.split_first_chunk::<11>()
    else {
        return;
    };
    let (width, height) = (1 + u32::from(w) % MAX_SIDE, 1 + u32::from(h) % MAX_SIDE);
    let params = webp::EncodeParams {
        lossless: flags & 1 != 0,
        quality: (flags & 2 != 0).then(|| f32::from_le_bytes([q0, q1, q2, q3])),
        method: (flags & 4 != 0).then_some(u32::from(method)),
    };

    // 메타데이터 세 조각을 차례로 떼어 낸다. 남은 바이트는 픽셀 재료다.
    let mut rest = rest;
    let mut take = |present: bool, len: u8| -> Option<Vec<u8>> {
        if !present {
            return None;
        }
        let n = usize::from(len).min(rest.len());
        let (chunk, tail) = rest.split_at(n);
        rest = tail;
        Some(chunk.to_vec())
    };
    let metadata = webp::MetadataChunks {
        icc_profile: take(flags & 8 != 0, l_icc),
        exif: take(flags & 16 != 0, l_exif),
        xmp: take(flags & 32 != 0, l_xmp),
    };
    let len = (width * height) as usize * webp::BYTES_PER_PIXEL;
    let pixels: Vec<u8> = if rest.is_empty() {
        vec![0; len]
    } else {
        rest.iter().copied().cycle().take(len).collect()
    };

    // 서버와 같은 순서: 옵션 검사(WebPValidateConfig, encode_still_rgba 안에서) → 인코딩 → 메타데이터.
    let Ok(encoded) = webp::encode_still_rgba(&pixels, width, height, &params) else {
        return;
    };
    let Ok(file) = webp::add_metadata(encoded, &metadata) else {
        return;
    };

    // 성질 1: lossless는 픽셀 정확 일치.
    let features = webp::features(&file).expect("encoded file must have a valid header");
    assert_eq!((features.width, features.height), (width, height));
    let decoded = webp::decode_still_rgba(&file, &features).expect("encoded file must decode");
    if params.lossless {
        assert!(
            decoded == pixels,
            "lossless round trip changed pixels ({width}x{height})"
        );
    }

    // 성질 2: 넣은 메타데이터를 같은 바이트로 다시 읽는다. 빈 페이로드는 libwebp가 청크로 넣지 않을 수 있어 비교에서 뺀다.
    let read = webp::metadata_chunks(&file, MAX_METADATA_BYTES).expect("metadata must be readable");
    let nonempty = |c: &Option<Vec<u8>>| c.clone().filter(|v| !v.is_empty());
    assert_eq!(
        nonempty(&read.icc_profile),
        nonempty(&metadata.icc_profile),
        "ICC changed"
    );
    assert_eq!(nonempty(&read.exif), nonempty(&metadata.exif), "EXIF changed");
    assert_eq!(nonempty(&read.xmp), nonempty(&metadata.xmp), "XMP changed");
});
