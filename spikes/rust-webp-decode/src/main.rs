//! 버릴 코드(스파이크). ADR-0002 결정 후 삭제한다. 결과는 docs/research/0003에 기록.
//!
//! libwebp-sys(원시 FFI)로 libwebp를 직접 호출해 다음만 확인한다.
//! 1. WebPGetFeatures로 크기를 먼저 읽고 상한을 검사한 뒤 디코딩한다(decompression bomb 방어 흉내).
//! 2. 정지 이미지 디코딩 결과(크기, 알파 여부, 첫 픽셀 RGBA).
//! 3. WebPMux로 ICCP/EXIF/XMP 청크를 읽을 수 있는지.
//! 4. WebPAnimDecoder로 애니메이션 프레임 수와 합성된 프레임을 얻을 수 있는지.
//!
//! 오류 처리와 안전한 래퍼 설계는 하지 않는다.

use libwebp_sys as w;
use std::ffi::{CStr, c_int};
use std::mem::MaybeUninit;
use std::{env, fs, process, ptr, slice};

/// 스파이크용 임의 상한(픽셀 수). 실제 값은 설계 단계에서 정한다.
const MAX_PIXELS: u64 = 100_000_000;

fn ver(v: c_int) -> String {
    format!("{}.{}.{}", (v >> 16) & 0xff, (v >> 8) & 0xff, v & 0xff)
}

fn features(data: &[u8]) -> Result<w::WebPBitstreamFeatures, String> {
    let mut f = MaybeUninit::<w::WebPBitstreamFeatures>::zeroed();
    // SAFETY: data는 호출 동안 유효하고, f는 쓰기 가능한 구조체다.
    let st = unsafe { w::WebPGetFeatures(data.as_ptr(), data.len(), f.as_mut_ptr()) };
    if st != w::VP8StatusCode::VP8_STATUS_OK {
        return Err(format!("WebPGetFeatures failed: {st:?}"));
    }
    // SAFETY: 성공 시 libwebp가 모든 필드를 채운다.
    Ok(unsafe { f.assume_init() })
}

fn guard(width: c_int, height: c_int) -> Result<(), String> {
    let px = width as u64 * height as u64;
    if px > MAX_PIXELS {
        return Err(format!(
            "too large: {width}x{height} = {px} px > {MAX_PIXELS}"
        ));
    }
    Ok(())
}

fn decode_still(data: &[u8]) -> Result<(), String> {
    let f = features(data)?;
    println!(
        "features: width={} height={} has_alpha={} has_animation={} format={}",
        f.width, f.height, f.has_alpha, f.has_animation, f.format
    );
    guard(f.width, f.height)?;
    println!("guard: ok (<= {MAX_PIXELS} px)");

    let (mut wd, mut ht): (c_int, c_int) = (0, 0);
    // SAFETY: 반환 포인터는 libwebp가 malloc한 RGBA 버퍼이며 WebPFree로 해제한다.
    let p = unsafe { w::WebPDecodeRGBA(data.as_ptr(), data.len(), &mut wd, &mut ht) };
    if p.is_null() {
        return Err("WebPDecodeRGBA failed".into());
    }
    let len = wd as usize * ht as usize * 4;
    // SAFETY: p는 len 바이트 이상 유효하다(width*height*4).
    let px = unsafe { slice::from_raw_parts(p, len) };
    println!(
        "decoded: {}x{} first_pixel_rgba=({}, {}, {}, {}) bytes={}",
        wd, ht, px[0], px[1], px[2], px[3], len
    );
    // SAFETY: p는 libwebp가 할당했다.
    unsafe { w::WebPFree(p.cast()) };
    Ok(())
}

fn read_chunks(data: &[u8]) -> Result<(), String> {
    let input = w::WebPData {
        bytes: data.as_ptr(),
        size: data.len(),
    };
    // WebPMuxCreate는 C 헤더의 static inline이라 바인딩에 없다. *Internal + ABI 상수를 직접 넘긴다.
    // SAFETY: copy_data=1로 내부 복사본을 만들게 한다(입력 수명과 분리).
    let mux = unsafe { w::WebPMuxCreateInternal(&input, 1, w::WEBP_MUX_ABI_VERSION as c_int) };
    if mux.is_null() {
        return Err("WebPMuxCreate failed".into());
    }
    let fourccs: [&CStr; 3] = [c"ICCP", c"EXIF", c"XMP "];
    for cc in fourccs {
        let mut chunk = w::WebPData::default();
        // SAFETY: mux는 유효하고, chunk는 mux 내부 메모리를 가리킨다(해제 불필요).
        let e = unsafe { w::WebPMuxGetChunk(mux, cc.as_ptr(), &mut chunk) };
        let name = cc.to_str().unwrap_or("?");
        if e == w::WebPMuxError::WEBP_MUX_OK {
            println!("mux chunk {name:?}: {} bytes", chunk.size);
        } else {
            println!("mux chunk {name:?}: {e:?}");
        }
    }
    // SAFETY: WebPMuxCreate로 만든 객체다.
    unsafe { w::WebPMuxDelete(mux) };
    Ok(())
}

fn decode_anim(data: &[u8]) -> Result<(), String> {
    let f = features(data)?;
    println!(
        "features: width={} height={} has_alpha={} has_animation={}",
        f.width, f.height, f.has_alpha, f.has_animation
    );
    guard(f.width, f.height)?;

    let input = w::WebPData {
        bytes: data.as_ptr(),
        size: data.len(),
    };
    let mut opts = MaybeUninit::<w::WebPAnimDecoderOptions>::zeroed();
    // SAFETY: opts는 쓰기 가능하다. 반환값 0은 ABI 불일치.
    if unsafe { w::WebPAnimDecoderOptionsInit(opts.as_mut_ptr()) } == 0 {
        return Err("WebPAnimDecoderOptionsInit failed".into());
    }
    // SAFETY: Init 성공 후 초기화되어 있다.
    let mut opts = unsafe { opts.assume_init() };
    opts.color_mode = w::WEBP_CSP_MODE::MODE_RGBA;
    opts.use_threads = 0;

    // 디코더는 input.bytes를 수명 동안 참조한다. data(&[u8])가 이 함수 끝까지 살아 있으므로 안전하다.
    // SAFETY: input/opts는 유효하다.
    let dec = unsafe { w::WebPAnimDecoderNew(&input, &opts) };
    if dec.is_null() {
        return Err("WebPAnimDecoderNew failed".into());
    }
    let mut info = w::WebPAnimInfo::default();
    // SAFETY: dec는 유효하다.
    unsafe { w::WebPAnimDecoderGetInfo(dec, &mut info) };
    println!(
        "anim: canvas={}x{} frame_count={} loop_count={} bgcolor=0x{:08X}",
        info.canvas_width, info.canvas_height, info.frame_count, info.loop_count, info.bgcolor
    );
    let mut n = 0;
    // SAFETY: dec는 유효하다. buf는 디코더 소유이며 다음 GetNext/Delete 전까지만 유효하다.
    while unsafe { w::WebPAnimDecoderHasMoreFrames(dec) } != 0 {
        let mut buf: *mut u8 = ptr::null_mut();
        let mut ts: c_int = 0;
        if unsafe { w::WebPAnimDecoderGetNext(dec, &mut buf, &mut ts) } == 0 {
            unsafe { w::WebPAnimDecoderDelete(dec) };
            return Err("WebPAnimDecoderGetNext failed".into());
        }
        let px = unsafe { slice::from_raw_parts(buf, 4) };
        n += 1;
        println!(
            "  frame {n}: timestamp_ms={ts} first_pixel_rgba=({}, {}, {}, {})",
            px[0], px[1], px[2], px[3]
        );
    }
    // SAFETY: WebPAnimDecoderNew로 만든 객체다.
    unsafe { w::WebPAnimDecoderDelete(dec) };
    println!("anim: decoded_frames={n}");
    Ok(())
}

fn main() {
    let args: Vec<String> = env::args().collect();
    if args.len() < 2 {
        eprintln!("usage: {} <still.webp> [anim.webp]", args[0]);
        process::exit(2);
    }
    // SAFETY: 인자 없는 버전 조회 함수다.
    unsafe {
        println!(
            "libwebp linked: decoder={} encoder={} demux={} mux={}",
            ver(w::WebPGetDecoderVersion()),
            ver(w::WebPGetEncoderVersion()),
            ver(w::WebPGetDemuxVersion()),
            ver(w::WebPGetMuxVersion())
        );
    }
    let run = || -> Result<(), String> {
        println!("== still: {}", args[1]);
        let still = fs::read(&args[1]).map_err(|e| e.to_string())?;
        decode_still(&still)?;
        read_chunks(&still)?;
        if let Some(p) = args.get(2) {
            println!("== anim: {p}");
            let anim = fs::read(p).map_err(|e| e.to_string())?;
            decode_anim(&anim)?;
        }
        Ok(())
    };
    if let Err(e) = run() {
        eprintln!("error: {e}");
        process::exit(1);
    }
}
