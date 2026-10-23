//! 서버가 Decode 한 번에 쓰는 libwebp 디코딩 시간을 gRPC 없이 잰다(Phase 4 사이드카 비용 분해, bench/README.md).
//!
//! 사용: `cargo run --release --target x86_64-unknown-linux-musl --example decode_time -- <반복 수> <file.webp>...`
//!
//! 서버의 Decode와 같은 함수(`features` + `decode_still_rgba`)를 단일 스레드로 부른다. 출력 버퍼 할당·해제를 포함한다.
//! 컨테이너 이미지와 같은 할당자(musl)를 쓰려면 musl 타깃으로 빌드한다. 반복 수의 1/5을 먼저 버리고(워밍업) 나머지의
//! 중앙값·최솟값·90백분위를 ms로 출력한다.

use std::time::{Duration, Instant};

fn main() {
    let args: Vec<String> = std::env::args().skip(1).collect();
    let Some(iterations) = args.first().and_then(|s| s.parse::<usize>().ok()).filter(|n| *n > 0) else {
        eprintln!("usage: decode_time <iterations> <file.webp>...");
        std::process::exit(2);
    };
    println!("file\tmedian_ms\tmin_ms\tp90_ms\titerations");
    for path in &args[1..] {
        let data = std::fs::read(path).unwrap_or_else(|e| panic!("read {path}: {e}"));
        let decode_once = || {
            let features = webp_imageio_server::webp::features(&data).expect("features");
            let pixels = webp_imageio_server::webp::decode_still_rgba(&data, &features).expect("decode");
            std::hint::black_box(pixels);
        };
        for _ in 0..(iterations / 5).max(1) {
            decode_once();
        }
        let mut times: Vec<Duration> = (0..iterations)
            .map(|_| {
                let start = Instant::now();
                decode_once();
                start.elapsed()
            })
            .collect();
        times.sort();
        let ms = |d: Duration| d.as_secs_f64() * 1000.0;
        println!(
            "{path}\t{:.3}\t{:.3}\t{:.3}\t{iterations}",
            ms(times[times.len() / 2]),
            ms(times[0]),
            ms(times[times.len() * 9 / 10])
        );
    }
}
