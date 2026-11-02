//! 서버가 Decode 한 번에 쓰는 libwebp 디코딩 시간을 gRPC 없이 잰다(Phase 4 사이드카 비용 분해, bench/README.md).
//!
//! 사용: `cargo run --release --example decode_time -- <반복 수> <file.webp>...`
//!
//! 서버의 Decode와 같은 함수(`features` + `decode_still_rgba`)를 단일 스레드로 부른다. 출력 버퍼 할당·해제를 포함한다.
//! 컨테이너 이미지는 glibc 할당자다(ADR-0021). 기본(gnu) 타깃이 같은 할당자이고, musl과 비교하려면
//! `--target x86_64-unknown-linux-musl`로 빌드한다. 반복 수의 1/5을 먼저 버리고(워밍업) 나머지의
//! 중앙값·최솟값·90백분위를 ms로 출력한다. `DECODE_TIME_REUSE=1`이면 출력 버퍼를 재사용한다.

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
        // DECODE_TIME_REUSE=1이면 출력 버퍼 하나를 계속 재사용한다(버퍼 재사용 효과 측정용, Phase 5-2).
        let reuse = std::env::var("DECODE_TIME_REUSE").is_ok_and(|v| v == "1");
        let mut buffer = Vec::new();
        let mut decode_once = || {
            let features = webp_imageio_server::webp::features(&data).expect("features");
            if reuse {
                webp_imageio_server::webp::decode_still_rgba_into(&data, &features, &mut buffer).expect("decode");
                std::hint::black_box(&buffer);
            } else {
                let pixels = webp_imageio_server::webp::decode_still_rgba(&data, &features).expect("decode");
                std::hint::black_box(pixels);
            }
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
