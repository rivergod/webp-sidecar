//! libwebp 인코더 자체의 최고 메모리(VmHWM)를 gRPC 없이 잰다(docs/research/0007-server-memory-budget.md).
//!
//! 사용: `cargo run --release --example encode_rss -- <변 픽셀> <lossy|lossless>`
//! 입력은 압축이 거의 안 되는 무작위 잡음(최악 경우)이다. Linux `/proc/self/status`가 필요하다.

fn peak_rss() -> String {
    std::fs::read_to_string("/proc/self/status")
        .ok()
        .and_then(|s| s.lines().find(|l| l.starts_with("VmHWM")).map(str::to_string))
        .unwrap_or_else(|| "VmHWM: n/a".into())
}

fn main() {
    let args: Vec<String> = std::env::args().collect();
    let (Some(side), Some(mode)) = (args.get(1).and_then(|s| s.parse::<u32>().ok()), args.get(2)) else {
        eprintln!("usage: encode_rss <side> <lossy|lossless>");
        std::process::exit(2);
    };

    // 결정적인 무작위 잡음 RGBA(alpha는 255).
    let mut pixels = vec![0u8; side as usize * side as usize * 4];
    let mut state: u32 = 0x1234_5678;
    for (i, b) in pixels.iter_mut().enumerate() {
        state ^= state << 13;
        state ^= state >> 17;
        state ^= state << 5;
        *b = if i % 4 == 3 { 255 } else { (state & 0xff) as u8 };
    }
    println!("after input  {}", peak_rss());

    let params = webp_imageio_server::webp::EncodeParams {
        lossless: mode == "lossless",
        quality: None,
        method: None,
    };
    let out = webp_imageio_server::webp::encode_still_rgba(&pixels, side, side, &params).expect("encode");
    println!("after encode {} output_bytes={}", peak_rss(), out.len());
}
