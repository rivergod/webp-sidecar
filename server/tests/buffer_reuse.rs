//! 출력 버퍼 재사용(ADR-0018)에서 이전 요청의 픽셀이 다음 결과에 섞이지 않는지 확인한다.

use std::path::PathBuf;
use webp_imageio_server::webp;

fn sample(rel: &str) -> Vec<u8> {
    let path = PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../samples").join(rel);
    std::fs::read(&path).unwrap_or_else(|e| panic!("read {}: {e}", path.display()))
}

#[test]
fn decode_into_reused_buffer_after_failure_matches_fresh_decode() {
    // Arrange: 큰 이미지로 버퍼를 채운 뒤, 헤더는 정상이지만 디코딩이 실패하는 입력을 같은 버퍼에 디코딩한다
    let big = sample("generated/still/lossy_3840x2160.webp");
    let broken = sample("malformed/trunc_alph_mid.webp");
    let small = sample("generated/still/lossy_17x9.webp");
    let mut buffer = Vec::new();
    webp::decode_still_rgba_into(&big, &webp::features(&big).unwrap(), &mut buffer).unwrap();
    let broken_features = webp::features(&broken).expect("header of the truncated sample must parse");
    let failed = webp::decode_still_rgba_into(&broken, &broken_features, &mut buffer);
    assert!(failed.is_err());
    assert!(buffer.is_empty(), "a failed decode must leave no visible bytes");
    let small_features = webp::features(&small).unwrap();
    let fresh = webp::decode_still_rgba(&small, &small_features).unwrap();

    // Act
    webp::decode_still_rgba_into(&small, &small_features, &mut buffer).unwrap();

    // Assert
    assert_eq!(buffer, fresh);
}
