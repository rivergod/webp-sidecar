//! GetFeatures 경로(service.rs `get_features`): 컨테이너 구조와 메타데이터 청크를 읽는다. 픽셀은 디코딩하지 않는다.
#![no_main]

use libfuzzer_sys::fuzz_target;
use webp_imageio_server::webp;

/// 서버 기본 상한(ADR-0012). 이 경로는 픽셀을 할당하지 않으므로 기본값 그대로 쓴다.
const MAX_FRAMES: u32 = 1000;
const MAX_METADATA_BYTES: u64 = 2 * 1024 * 1024;

fuzz_target!(|data: &[u8]| {
    let _ = webp::container_info(data, MAX_FRAMES);
    let _ = webp::metadata_chunks(data, MAX_METADATA_BYTES);
});
