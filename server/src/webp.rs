//! libwebp FFI 경계. 이 크레이트에서 `unsafe`는 이 모듈에만 둔다(ADR-0002 구현 조건).
//!
//! - libwebp-sys는 C enum을 Rust enum으로 생성한다. libwebp가 예상 밖의 값을 돌려주면 UB가 되므로,
//!   상태 enum을 **반환**하는 함수는 이 모듈에서 `c_int` 반환으로 다시 선언해 쓴다(ADR-0002).
//! - 구조체 안의 enum 필드는 libwebp가 정의된 값만 쓰지만, 읽을 때는 정수로 읽는다.
//! - 모든 함수는 동기(blocking)이고 CPU를 오래 쓸 수 있다. 호출자는 비동기 런타임 스레드가 아니라
//!   blocking 스레드 풀에서 부른다(ADR-0012).
//! - 스레드 안전성: 이 모듈의 함수는 공유 상태가 없어 여러 스레드에서 동시에 불러도 된다.

use libwebp_sys as sys;
use std::ffi::{c_int, c_void};
use std::fmt;
use std::mem::MaybeUninit;

// libwebp-sys 선언과 같은 심볼을 정수 반환으로 다시 선언한다. 링크는 libwebp-sys가 만든 정적 라이브러리로 된다.
unsafe extern "C" {
    #[link_name = "WebPGetFeaturesInternal"]
    fn get_features_internal(
        data: *const u8,
        data_size: usize,
        features: *mut sys::WebPBitstreamFeatures,
        version: c_int,
    ) -> c_int;

    #[link_name = "WebPDecode"]
    fn decode_raw(data: *const u8, data_size: usize, config: *mut sys::WebPDecoderConfig) -> c_int;
}

// VP8StatusCode 값 (libwebp src/webp/decode.h).
const VP8_STATUS_OK: c_int = 0;
const VP8_STATUS_OUT_OF_MEMORY: c_int = 1;
const VP8_STATUS_INVALID_PARAM: c_int = 2;
const VP8_STATUS_BITSTREAM_ERROR: c_int = 3;
const VP8_STATUS_UNSUPPORTED_FEATURE: c_int = 4;
const VP8_STATUS_NOT_ENOUGH_DATA: c_int = 7;

// WebPEncodingError 값 (libwebp src/webp/encode.h).
const VP8_ENC_ERROR_OUT_OF_MEMORY: u32 = 1;
const VP8_ENC_ERROR_BITSTREAM_OUT_OF_MEMORY: u32 = 2;

/// RGBA8 한 픽셀의 바이트 수(ADR-0004).
pub const BYTES_PER_PIXEL: usize = 4;

/// libwebp가 다룰 수 있는 최대 너비·높이. 단위: 픽셀. (`WEBP_MAX_DIMENSION`)
pub const MAX_DIMENSION: u32 = sys::WEBP_MAX_DIMENSION;

/// libwebp 호출 실패의 분류. gRPC status로 바꾸는 일은 호출자(service 모듈)가 한다.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum WebpError {
    /// 입력이 WebP가 아니거나 손상·잘림, 또는 지원하지 않는 비트스트림 기능.
    InvalidData(String),
    /// 호출자가 준 인자(크기, 품질 등)가 잘못됐다.
    InvalidArgument(String),
    /// 메모리 할당 실패.
    OutOfMemory(String),
    /// 서버 상한(프레임 수, 메타데이터 크기 등)을 넘었다.
    LimitExceeded(String),
    /// 그 밖의 libwebp 내부 오류(서버 버그 가능성).
    Internal(String),
}

impl fmt::Display for WebpError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::InvalidData(m) => write!(f, "invalid WebP data: {m}"),
            Self::InvalidArgument(m) => write!(f, "invalid argument: {m}"),
            Self::OutOfMemory(m) => write!(f, "out of memory: {m}"),
            Self::LimitExceeded(m) => write!(f, "limit exceeded: {m}"),
            Self::Internal(m) => write!(f, "internal libwebp error: {m}"),
        }
    }
}

impl std::error::Error for WebpError {}

/// 비트스트림 압축 방식. `WebPBitstreamFeatures.format`에 대응한다.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Format {
    /// 알 수 없음 또는 애니메이션에서 프레임마다 다름.
    UndefinedOrMixed,
    /// VP8 (lossy).
    Lossy,
    /// VP8L (lossless).
    Lossless,
}

/// 픽셀을 디코딩하지 않고 헤더에서 읽은 특성.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Features {
    /// 너비(애니메이션은 캔버스 너비). 단위: 픽셀.
    pub width: u32,
    /// 높이(애니메이션은 캔버스 높이). 단위: 픽셀.
    pub height: u32,
    /// alpha가 있으면 true.
    pub has_alpha: bool,
    /// 애니메이션이면 true.
    pub has_animation: bool,
    /// 압축 방식.
    pub format: Format,
}

impl Features {
    /// width × height. 단위: 픽셀.
    pub fn pixel_count(&self) -> u64 {
        u64::from(self.width) * u64::from(self.height)
    }
}

/// 링크된 libwebp 라이브러리 버전("major.minor.revision").
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Versions {
    /// 디코더 버전.
    pub decoder: String,
    /// 인코더 버전.
    pub encoder: String,
    /// demux 버전.
    pub demux: String,
    /// mux 버전.
    pub mux: String,
}

/// 인코더 품질 설정. `None`이면 libwebp 기본값(`WebPConfigInit`: lossy, quality 75, method 4)을 쓴다.
#[derive(Debug, Clone, Copy, PartialEq, Default)]
pub struct EncodeParams {
    /// true면 lossless(VP8L).
    pub lossless: bool,
    /// 0–100. lossy는 화질, lossless는 압축 노력.
    pub quality: Option<f32>,
    /// 0(빠름)–6(느림, 더 작음).
    pub method: Option<u32>,
}

fn version_string(v: c_int) -> String {
    format!("{}.{}.{}", (v >> 16) & 0xff, (v >> 8) & 0xff, v & 0xff)
}

/// 링크된 libwebp 버전을 돌려준다.
pub fn versions() -> Versions {
    // SAFETY: 인자 없는 순수 조회 함수다.
    unsafe {
        Versions {
            decoder: version_string(sys::WebPGetDecoderVersion()),
            encoder: version_string(sys::WebPGetEncoderVersion()),
            demux: version_string(sys::WebPGetDemuxVersion()),
            mux: version_string(sys::WebPGetMuxVersion()),
        }
    }
}

fn status_error(op: &str, status: c_int) -> WebpError {
    match status {
        // libwebp 1.6.0은 일부 손상 파일(ALPH 예약 비트 등)에도 이 값을 돌려준다(samples/manifest.json, malformed/alph_*).
        VP8_STATUS_OUT_OF_MEMORY => WebpError::OutOfMemory(format!(
            "{op}: VP8_STATUS_OUT_OF_MEMORY (libwebp also reports this for some corrupt files)"
        )),
        VP8_STATUS_INVALID_PARAM => WebpError::Internal(format!("{op}: VP8_STATUS_INVALID_PARAM")),
        VP8_STATUS_BITSTREAM_ERROR => WebpError::InvalidData(format!("{op}: bitstream error")),
        VP8_STATUS_UNSUPPORTED_FEATURE => WebpError::InvalidData(format!("{op}: unsupported feature")),
        VP8_STATUS_NOT_ENOUGH_DATA => WebpError::InvalidData(format!("{op}: truncated data")),
        other => WebpError::Internal(format!("{op}: unexpected VP8StatusCode {other}")),
    }
}

/// 헤더만 읽어 특성을 돌려준다. 픽셀을 디코딩하지 않으므로 큰 선언 크기에도 메모리를 쓰지 않는다
/// (decompression bomb 방어의 첫 단계 — docs/design/grpc-api.md §3).
///
/// 오류: WebP가 아니거나 헤더가 손상·잘림이면 [`WebpError::InvalidData`].
pub fn features(data: &[u8]) -> Result<Features, WebpError> {
    let mut f = MaybeUninit::<sys::WebPBitstreamFeatures>::zeroed();
    // SAFETY: data는 호출 동안 유효하고 f는 쓰기 가능한 구조체다. 반환값은 정수로 받는다.
    let status = unsafe {
        get_features_internal(
            data.as_ptr(),
            data.len(),
            f.as_mut_ptr(),
            sys::WEBP_DECODER_ABI_VERSION as c_int,
        )
    };
    if status != VP8_STATUS_OK {
        return Err(status_error("WebPGetFeatures", status));
    }
    // SAFETY: 성공하면 libwebp가 모든 필드를 채운다(정수 필드만 있다).
    let f = unsafe { f.assume_init() };
    let format = match f.format {
        1 => Format::Lossy,
        2 => Format::Lossless,
        _ => Format::UndefinedOrMixed,
    };
    Ok(Features {
        width: f.width as u32,
        height: f.height as u32,
        has_alpha: f.has_alpha != 0,
        has_animation: f.has_animation != 0,
        format,
    })
}

/// RGBA8 버퍼(width × height × 4바이트)를 할당한다. 할당 실패를 프로세스 abort가 아니라 오류로 돌려준다.
pub fn alloc_rgba(width: u32, height: u32) -> Result<Vec<u8>, WebpError> {
    let len = rgba_len(width, height)?;
    let mut buf = Vec::new();
    buf.try_reserve_exact(len)
        .map_err(|_| WebpError::OutOfMemory(format!("cannot allocate {len} bytes for {width}x{height} RGBA")))?;
    buf.resize(len, 0);
    Ok(buf)
}

fn rgba_len(width: u32, height: u32) -> Result<usize, WebpError> {
    usize::try_from(u64::from(width) * u64::from(height) * BYTES_PER_PIXEL as u64)
        .map_err(|_| WebpError::InvalidArgument(format!("{width}x{height} RGBA does not fit in memory")))
}

/// 정지 이미지를 RGBA8(비-premultiplied, stride = width × 4)로 디코딩한다(ADR-0004).
///
/// `features`는 같은 `data`로 [`features`]를 불러 얻은 값이어야 한다. 호출자는 그 크기로 상한을 먼저 검사해야 한다.
/// 애니메이션은 이 함수로 디코딩하지 않는다(첫 프레임만 나오므로).
///
/// 오류: 손상·잘림은 [`WebpError::InvalidData`], 버퍼 할당 실패는 [`WebpError::OutOfMemory`].
pub fn decode_still_rgba(data: &[u8], features: &Features) -> Result<Vec<u8>, WebpError> {
    if features.has_animation {
        return Err(WebpError::InvalidArgument(
            "animated WebP must be decoded with the animation decoder".into(),
        ));
    }
    let mut pixels = alloc_rgba(features.width, features.height)?;
    let stride = features.width as usize * BYTES_PER_PIXEL;

    // 출력 버퍼를 직접 할당해 넘긴다(external memory). libwebp가 따로 큰 버퍼를 잡지 않는다.
    let mut config = MaybeUninit::<sys::WebPDecoderConfig>::zeroed();
    // SAFETY: config는 쓰기 가능하다. false는 ABI 불일치다.
    if !unsafe { sys::WebPInitDecoderConfig(config.as_mut_ptr()) } {
        return Err(WebpError::Internal("WebPInitDecoderConfig: ABI mismatch".into()));
    }
    // SAFETY: 초기화가 성공했다.
    let mut config = unsafe { config.assume_init() };
    config.output.colorspace = sys::WEBP_CSP_MODE::MODE_RGBA;
    config.output.is_external_memory = 1;
    config.output.u.RGBA = sys::WebPRGBABuffer {
        rgba: pixels.as_mut_ptr(),
        stride: stride as c_int,
        size: pixels.len(),
    };
    config.options.use_threads = 0;

    // SAFETY: data와 pixels는 호출 동안 유효하고, pixels는 stride × height 바이트다.
    let status = unsafe { decode_raw(data.as_ptr(), data.len(), &mut config) };
    // SAFETY: external memory라 libwebp가 해제할 내부 버퍼는 없지만, 규약대로 호출한다.
    unsafe { sys::WebPFreeDecBuffer(&mut config.output) };
    if status != VP8_STATUS_OK {
        return Err(status_error("WebPDecode", status));
    }
    Ok(pixels)
}

/// 출력 바이트를 모으는 `WebPMemoryWriter`. Drop에서 libwebp 메모리를 해제한다.
struct MemoryWriter(sys::WebPMemoryWriter);

impl MemoryWriter {
    fn new() -> Self {
        let mut w = MaybeUninit::<sys::WebPMemoryWriter>::zeroed();
        // SAFETY: w는 쓰기 가능하다. Init은 필드를 0으로 채운다.
        unsafe { sys::WebPMemoryWriterInit(w.as_mut_ptr()) };
        // SAFETY: 위에서 초기화했다.
        Self(unsafe { w.assume_init() })
    }

    fn to_vec(&self) -> Result<Vec<u8>, WebpError> {
        if self.0.mem.is_null() || self.0.size == 0 {
            return Err(WebpError::Internal("encoder produced no output".into()));
        }
        let mut out = Vec::new();
        out.try_reserve_exact(self.0.size)
            .map_err(|_| WebpError::OutOfMemory(format!("cannot allocate {} bytes for output", self.0.size)))?;
        // SAFETY: mem은 size 바이트 이상 유효하다(libwebp가 쓴 출력).
        out.extend_from_slice(unsafe { std::slice::from_raw_parts(self.0.mem, self.0.size) });
        Ok(out)
    }
}

impl Drop for MemoryWriter {
    fn drop(&mut self) {
        // SAFETY: Init으로 만든 writer다. mem이 null이어도 안전하다.
        unsafe { sys::WebPMemoryWriterClear(&mut self.0) };
    }
}

/// `WebPPicture`. Drop에서 import한 픽셀 메모리를 해제한다.
struct Picture(sys::WebPPicture);

impl Picture {
    fn from_rgba(pixels: &[u8], width: u32, height: u32, use_argb: bool) -> Result<Self, WebpError> {
        let mut pic = MaybeUninit::<sys::WebPPicture>::zeroed();
        // SAFETY: pic은 쓰기 가능하다. libwebp-sys의 WebPPictureInit은 디코더 ABI 상수를 넘기므로
        // (research/0003) 인코더 ABI 상수로 *Internal을 직접 부른다.
        if unsafe { sys::WebPPictureInitInternal(pic.as_mut_ptr(), sys::WEBP_ENCODER_ABI_VERSION as c_int) } == 0 {
            return Err(WebpError::Internal("WebPPictureInit: ABI mismatch".into()));
        }
        // SAFETY: 초기화가 성공했다.
        let mut pic = Self(unsafe { pic.assume_init() });
        pic.0.width = width as c_int;
        pic.0.height = height as c_int;
        pic.0.use_argb = c_int::from(use_argb);
        let stride = (width as usize * BYTES_PER_PIXEL) as c_int;
        // SAFETY: pixels는 width × height × 4바이트이고 import는 내용을 복사한다.
        if unsafe { sys::WebPPictureImportRGBA(&mut pic.0, pixels.as_ptr(), stride) } == 0 {
            return Err(WebpError::OutOfMemory("WebPPictureImportRGBA failed".into()));
        }
        Ok(pic)
    }

    /// `error_code`(enum 필드)를 정수로 읽는다.
    fn error_code(&self) -> u32 {
        // SAFETY: repr(u32) enum 필드의 메모리를 u32로 읽는다. 어떤 값이든 u32로는 유효하다.
        unsafe { std::ptr::addr_of!(self.0.error_code).cast::<u32>().read() }
    }
}

impl Drop for Picture {
    fn drop(&mut self) {
        // SAFETY: Init/Import로 만든 picture다.
        unsafe { sys::WebPPictureFree(&mut self.0) };
    }
}

/// 검증된 `WebPConfig`를 만든다. 범위 밖 값은 [`WebpError::InvalidArgument`].
pub(crate) fn encoder_config(params: &EncodeParams) -> Result<sys::WebPConfig, WebpError> {
    if let Some(q) = params.quality
        && !(0.0..=100.0).contains(&q)
    {
        return Err(WebpError::InvalidArgument(format!("quality must be 0-100, got {q}")));
    }
    if let Some(m) = params.method
        && m > 6
    {
        return Err(WebpError::InvalidArgument(format!("method must be 0-6, got {m}")));
    }
    let mut config = MaybeUninit::<sys::WebPConfig>::zeroed();
    // SAFETY: config는 쓰기 가능하다. WebPConfigInit과 같은 인자(기본 preset, quality 75)로 부른다.
    let ok = unsafe {
        sys::WebPConfigInitInternal(
            config.as_mut_ptr(),
            sys::WebPPreset::WEBP_PRESET_DEFAULT,
            75.0,
            sys::WEBP_ENCODER_ABI_VERSION as c_int,
        )
    };
    if ok == 0 {
        return Err(WebpError::Internal("WebPConfigInit: ABI mismatch".into()));
    }
    // SAFETY: 초기화가 성공했다.
    let mut config = unsafe { config.assume_init() };
    if params.lossless {
        config.lossless = 1;
        // ADR-0004: lossless 왕복은 픽셀이 바뀌면 안 된다. 투명 영역의 RGB도 보존한다.
        config.exact = 1;
    }
    if let Some(q) = params.quality {
        config.quality = q;
    }
    if let Some(m) = params.method {
        config.method = m as c_int;
    }
    config.thread_level = 0;
    // SAFETY: config는 초기화된 구조체다.
    if unsafe { sys::WebPValidateConfig(&config) } == 0 {
        return Err(WebpError::InvalidArgument("invalid encoder configuration".into()));
    }
    Ok(config)
}

/// 너비·높이가 libwebp 범위(1..=16383) 안인지 확인한다.
pub fn check_dimensions(width: u32, height: u32) -> Result<(), WebpError> {
    if width == 0 || height == 0 || width > MAX_DIMENSION || height > MAX_DIMENSION {
        return Err(WebpError::InvalidArgument(format!(
            "dimensions must be 1..={MAX_DIMENSION}, got {width}x{height}"
        )));
    }
    Ok(())
}

/// RGBA8 픽셀(비-premultiplied, stride = width × 4)을 정지 WebP 파일로 인코딩한다.
///
/// 오류: 크기·품질이 범위 밖이면 [`WebpError::InvalidArgument`], 메모리 부족은 [`WebpError::OutOfMemory`].
pub fn encode_still_rgba(pixels: &[u8], width: u32, height: u32, params: &EncodeParams) -> Result<Vec<u8>, WebpError> {
    check_dimensions(width, height)?;
    if pixels.len() != rgba_len(width, height)? {
        return Err(WebpError::InvalidArgument(format!(
            "expected {} bytes for {width}x{height} RGBA, got {}",
            rgba_len(width, height)?,
            pixels.len()
        )));
    }
    let config = encoder_config(params)?;
    // lossless는 ARGB 경로를 쓴다(libwebp 권장). lossy는 YUV로 변환된다.
    let mut pic = Picture::from_rgba(pixels, width, height, params.lossless)?;

    // 출력은 메모리 writer로 모은다.
    let mut writer = MemoryWriter::new();
    pic.0.writer = Some(sys::WebPMemoryWrite);
    pic.0.custom_ptr = std::ptr::addr_of_mut!(writer.0).cast::<c_void>();

    // SAFETY: config는 검증됐고 pic과 writer는 이 호출 동안 살아 있다.
    if unsafe { sys::WebPEncode(&config, &mut pic.0) } == 0 {
        return Err(match pic.error_code() {
            VP8_ENC_ERROR_OUT_OF_MEMORY | VP8_ENC_ERROR_BITSTREAM_OUT_OF_MEMORY => {
                WebpError::OutOfMemory(format!("WebPEncode error {}", pic.error_code()))
            }
            code => WebpError::Internal(format!("WebPEncode error {code}")),
        });
    }
    writer.to_vec()
}

// --- demux: 컨테이너 구조(캔버스, 프레임, 메타데이터 청크) 읽기 ---

// WebPFeatureFlags 값 (libwebp src/webp/mux_types.h).
const ANIMATION_FLAG: u32 = 0x02;

/// 애니메이션 프레임 하나의 ANMF 정보(RFC 9649 §2.7.1.1). 정지 이미지는 캔버스 전체를 덮는 프레임 하나로 나온다.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct FrameInfo {
    /// 캔버스 안 x 오프셋. 단위: 픽셀.
    pub x_offset: u32,
    /// 캔버스 안 y 오프셋. 단위: 픽셀.
    pub y_offset: u32,
    /// 프레임 너비. 단위: 픽셀.
    pub width: u32,
    /// 프레임 높이. 단위: 픽셀.
    pub height: u32,
    /// 표시 시간. 단위: ms.
    pub duration_ms: u32,
    /// true면 표시 후 프레임 사각형을 배경색으로 지운다(dispose to background).
    pub dispose_to_background: bool,
    /// true면 alpha로 섞는다(alpha blend). false면 덮어쓴다.
    pub alpha_blend: bool,
    /// 프레임 비트스트림에 alpha가 있으면 true.
    pub has_alpha: bool,
    /// 프레임 비트스트림의 압축 방식.
    pub format: Format,
}

/// 컨테이너 전체 정보.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ContainerInfo {
    /// 헤더 특성(캔버스 크기, alpha, 애니메이션 여부).
    pub features: Features,
    /// 반복 횟수. 0은 무한.
    pub loop_count: u32,
    /// 배경색 0xAARRGGBB (ANIM 청크를 little-endian u32로 읽은 값, RFC 9649 §2.7.1.1).
    pub background_color_argb: u32,
    /// 프레임 정보. 표시 순서.
    pub frames: Vec<FrameInfo>,
}

impl ContainerInfo {
    /// 전체 압축 방식. 애니메이션에서 프레임마다 다르면 [`Format::UndefinedOrMixed`].
    pub fn overall_format(&self) -> Format {
        let mut formats = self.frames.iter().map(|f| f.format);
        match formats.next() {
            Some(first) if formats.all(|f| f == first) => first,
            Some(_) => Format::UndefinedOrMixed,
            None => self.features.format,
        }
    }
}

/// 메타데이터 청크 페이로드(RFC 9649 §2.7.1.4, §2.7.1.5). 해석하지 않은 원본 바이트다.
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct MetadataChunks {
    /// ICCP 청크 페이로드.
    pub icc_profile: Option<Vec<u8>>,
    /// EXIF 청크 페이로드.
    pub exif: Option<Vec<u8>>,
    /// XMP 청크 페이로드.
    pub xmp: Option<Vec<u8>>,
}

impl MetadataChunks {
    /// 세 페이로드 크기의 합. 단위: 바이트.
    pub fn total_len(&self) -> u64 {
        [&self.icc_profile, &self.exif, &self.xmp]
            .iter()
            .map(|c| c.as_ref().map_or(0, |v| v.len() as u64))
            .sum()
    }
}

/// `WebPDemuxer`. 입력 바이트를 복사하지 않고 참조하므로 수명이 `data`에 묶인다.
struct Demuxer<'a> {
    ptr: std::ptr::NonNull<sys::WebPDemuxer>,
    _data: std::marker::PhantomData<&'a [u8]>,
}

impl<'a> Demuxer<'a> {
    fn new(data: &'a [u8]) -> Result<Self, WebpError> {
        let input = sys::WebPData {
            bytes: data.as_ptr(),
            size: data.len(),
        };
        // SAFETY: input은 data를 가리키고, 반환된 demuxer는 data보다 오래 살지 않는다(PhantomData 수명).
        // allow_partial=0으로 완전한 파일만 받는다. state 출력은 쓰지 않는다(null 허용).
        let ptr =
            unsafe { sys::WebPDemuxInternal(&input, 0, std::ptr::null_mut(), sys::WEBP_DEMUX_ABI_VERSION as c_int) };
        std::ptr::NonNull::new(ptr)
            .map(|ptr| Self {
                ptr,
                _data: std::marker::PhantomData,
            })
            .ok_or_else(|| WebpError::InvalidData("WebPDemux: cannot parse container".into()))
    }

    fn get(&self, feature: sys::WebPFormatFeature) -> u32 {
        // SAFETY: ptr은 유효한 demuxer다.
        unsafe { sys::WebPDemuxGetI(self.ptr.as_ptr(), feature) }
    }

    fn frame(&self, number: u32) -> Result<FrameInfo, WebpError> {
        let mut iter = MaybeUninit::<sys::WebPIterator>::zeroed();
        // SAFETY: ptr은 유효하고 iter는 쓰기 가능하다. 프레임 번호는 1부터다.
        if unsafe { sys::WebPDemuxGetFrame(self.ptr.as_ptr(), number as c_int, iter.as_mut_ptr()) } == 0 {
            return Err(WebpError::InvalidData(format!("WebPDemuxGetFrame({number}) failed")));
        }
        // SAFETY: 성공하면 iter가 채워진다. enum 필드는 아래에서 정수로 읽는다.
        let mut iter = unsafe { iter.assume_init() };
        // SAFETY: repr(u32) enum 필드의 메모리를 u32로 읽는다(ADR-0002 구현 조건).
        let dispose = unsafe { std::ptr::addr_of!(iter.dispose_method).cast::<u32>().read() };
        // SAFETY: 위와 같다.
        let blend = unsafe { std::ptr::addr_of!(iter.blend_method).cast::<u32>().read() };
        // fragment는 프레임의 청크들(ALPH + VP8, 또는 VP8L)이다. 첫 FourCC로 압축 방식을 판단한다.
        // SAFETY: fragment는 demuxer가 참조하는 입력 안의 유효한 범위다.
        let fragment = unsafe { std::slice::from_raw_parts(iter.fragment.bytes, iter.fragment.size) };
        let format = match fragment.get(..4) {
            Some(b"VP8L") => Format::Lossless,
            Some(b"VP8 ") | Some(b"ALPH") => Format::Lossy,
            _ => Format::UndefinedOrMixed,
        };
        let info = FrameInfo {
            x_offset: iter.x_offset as u32,
            y_offset: iter.y_offset as u32,
            width: iter.width as u32,
            height: iter.height as u32,
            duration_ms: iter.duration as u32,
            // WEBP_MUX_DISPOSE_BACKGROUND = 1, WEBP_MUX_BLEND = 0 (libwebp src/webp/mux_types.h).
            dispose_to_background: dispose == 1,
            alpha_blend: blend == 0,
            has_alpha: iter.has_alpha != 0,
            format,
        };
        // SAFETY: GetFrame으로 얻은 iterator다.
        unsafe { sys::WebPDemuxReleaseIterator(&mut iter) };
        Ok(info)
    }

    fn chunk(&self, fourcc: &std::ffi::CStr) -> Result<Option<Vec<u8>>, WebpError> {
        let mut iter = MaybeUninit::<sys::WebPChunkIterator>::zeroed();
        // SAFETY: ptr은 유효하고 fourcc는 NUL 종료 4글자다. 청크 번호는 1부터다.
        if unsafe { sys::WebPDemuxGetChunk(self.ptr.as_ptr(), fourcc.as_ptr(), 1, iter.as_mut_ptr()) } == 0 {
            return Ok(None);
        }
        // SAFETY: 성공하면 iter가 채워진다.
        let mut iter = unsafe { iter.assume_init() };
        // SAFETY: chunk는 입력 안의 유효한 범위다. 복사해 수명을 끊는다.
        let bytes = unsafe { std::slice::from_raw_parts(iter.chunk.bytes, iter.chunk.size) };
        let mut out = Vec::new();
        let copied = out
            .try_reserve_exact(bytes.len())
            .map(|()| out.extend_from_slice(bytes));
        // SAFETY: GetChunk로 얻은 iterator다.
        unsafe { sys::WebPDemuxReleaseChunkIterator(&mut iter) };
        copied.map_err(|_| WebpError::OutOfMemory("cannot copy metadata chunk".into()))?;
        Ok(Some(out))
    }
}

impl Drop for Demuxer<'_> {
    fn drop(&mut self) {
        // SAFETY: WebPDemuxInternal로 만든 demuxer다.
        unsafe { sys::WebPDemuxDelete(self.ptr.as_ptr()) };
    }
}

/// 컨테이너 정보(캔버스, 반복, 배경색, 프레임 목록)를 읽는다. 픽셀은 디코딩하지 않는다.
///
/// 오류: 손상·잘림은 [`WebpError::InvalidData`], 프레임 수가 `max_frames`를 넘으면 [`WebpError::LimitExceeded`]
/// (프레임 목록을 만들기 전에 검사한다).
pub fn container_info(data: &[u8], max_frames: u32) -> Result<ContainerInfo, WebpError> {
    let features = features(data)?;
    let dmux = Demuxer::new(data)?;
    let frame_count = dmux.get(sys::WebPFormatFeature::WEBP_FF_FRAME_COUNT);
    if frame_count > max_frames {
        return Err(WebpError::LimitExceeded(format!(
            "{frame_count} frames > max_frames {max_frames}"
        )));
    }
    let is_animation = dmux.get(sys::WebPFormatFeature::WEBP_FF_FORMAT_FLAGS) & ANIMATION_FLAG != 0;
    let (loop_count, background_color_argb) = if is_animation {
        (
            dmux.get(sys::WebPFormatFeature::WEBP_FF_LOOP_COUNT),
            dmux.get(sys::WebPFormatFeature::WEBP_FF_BACKGROUND_COLOR),
        )
    } else {
        (0, 0)
    };
    let frames = (1..=frame_count)
        .map(|n| dmux.frame(n))
        .collect::<Result<Vec<_>, _>>()?;
    Ok(ContainerInfo {
        features,
        loop_count,
        background_color_argb,
        frames,
    })
}

/// ICCP/EXIF/XMP 청크 페이로드를 읽는다.
///
/// 오류: 손상은 [`WebpError::InvalidData`], 합계가 `max_metadata_bytes`를 넘으면 [`WebpError::LimitExceeded`].
pub fn metadata_chunks(data: &[u8], max_metadata_bytes: u64) -> Result<MetadataChunks, WebpError> {
    let dmux = Demuxer::new(data)?;
    let chunks = MetadataChunks {
        icc_profile: dmux.chunk(c"ICCP")?,
        exif: dmux.chunk(c"EXIF")?,
        xmp: dmux.chunk(c"XMP ")?,
    };
    if chunks.total_len() > max_metadata_bytes {
        return Err(WebpError::LimitExceeded(format!(
            "metadata {} bytes > max_metadata_bytes {max_metadata_bytes}",
            chunks.total_len()
        )));
    }
    Ok(chunks)
}

#[cfg(test)]
mod tests {
    use super::*;

    const STILL: &[u8] = include_bytes!("../../spikes/testdata/still.webp");
    const BOMB: &[u8] = include_bytes!("../../spikes/testdata/bomb_16384.webp");

    #[test]
    fn versions_linked_libwebp_reports_1_6_0() {
        // when
        let v = versions();

        // then
        assert_eq!(v.decoder, "1.6.0");
        assert_eq!(v.encoder, "1.6.0");
        assert_eq!(v.demux, "1.6.0");
        assert_eq!(v.mux, "1.6.0");
    }

    #[test]
    fn features_lossless_still_reads_header() {
        // when
        let f = features(STILL).unwrap();

        // then
        assert_eq!((f.width, f.height, f.has_alpha, f.has_animation), (4, 3, true, false));
        assert_eq!(f.format, Format::Lossless);
    }

    #[test]
    fn features_huge_declared_size_reads_without_allocating() {
        // when
        let f = features(BOMB).unwrap();

        // then
        assert_eq!(f.pixel_count(), 16384 * 16384);
    }

    #[test]
    fn features_garbage_is_invalid_data() {
        // when
        let err = features(b"RIFF\x04\x00\x00\x00WEBPxxxx").unwrap_err();

        // then
        assert!(matches!(err, WebpError::InvalidData(_)), "{err:?}");
    }

    #[test]
    fn decode_still_rgba_lossless_still_first_pixel_matches() {
        // given
        let f = features(STILL).unwrap();

        // when
        let px = decode_still_rgba(STILL, &f).unwrap();

        // then
        assert_eq!(px.len(), 4 * 3 * 4);
        assert_eq!(&px[..4], &[0x12, 0x34, 0x56, 0x78]);
    }

    #[test]
    fn decode_still_rgba_truncated_bitstream_is_invalid_data() {
        // given
        let truncated = &STILL[..STILL.len() - 20];
        let f = features(STILL).unwrap();

        // when
        let err = decode_still_rgba(truncated, &f).unwrap_err();

        // then
        assert!(matches!(err, WebpError::InvalidData(_)), "{err:?}");
    }

    #[test]
    fn encode_still_rgba_lossless_round_trips_exactly() {
        // given: 투명 픽셀의 RGB까지 포함한 4x3 패턴
        let pixels: Vec<u8> = (0..48u8).map(|i| i.wrapping_mul(37)).collect();
        let params = EncodeParams {
            lossless: true,
            ..Default::default()
        };

        // when
        let webp = encode_still_rgba(&pixels, 4, 3, &params).unwrap();

        // then
        let f = features(&webp).unwrap();
        assert_eq!(decode_still_rgba(&webp, &f).unwrap(), pixels);
    }

    #[test]
    fn encode_still_rgba_quality_out_of_range_is_invalid_argument() {
        // given
        let params = EncodeParams {
            quality: Some(101.0),
            ..Default::default()
        };

        // when
        let err = encode_still_rgba(&[0; 4], 1, 1, &params).unwrap_err();

        // then
        assert!(matches!(err, WebpError::InvalidArgument(_)), "{err:?}");
    }

    #[test]
    fn encode_still_rgba_wrong_pixel_length_is_invalid_argument() {
        // when
        let err = encode_still_rgba(&[0; 5], 1, 1, &EncodeParams::default()).unwrap_err();

        // then
        assert!(matches!(err, WebpError::InvalidArgument(_)), "{err:?}");
    }
    const ANIM: &[u8] = include_bytes!("../../spikes/testdata/anim.webp");

    #[test]
    fn container_info_animation_lists_frames() {
        // when
        let info = container_info(ANIM, 1000).unwrap();

        // then
        assert!(info.features.has_animation);
        assert_eq!(info.frames.len(), 3);
        assert!(info.frames.iter().all(|f| f.duration_ms == 100));
        assert_eq!(info.overall_format(), Format::Lossless);
    }

    #[test]
    fn container_info_too_many_frames_is_limit_exceeded() {
        // when
        let err = container_info(ANIM, 2).unwrap_err();

        // then
        assert!(matches!(err, WebpError::LimitExceeded(_)), "{err:?}");
    }

    #[test]
    fn container_info_still_is_single_full_frame() {
        // when
        let info = container_info(STILL, 1000).unwrap();

        // then
        assert_eq!(info.frames.len(), 1);
        assert_eq!((info.frames[0].width, info.frames[0].height), (4, 3));
    }

    #[test]
    fn metadata_chunks_still_with_metadata_returns_payloads() {
        // when
        let m = metadata_chunks(STILL, 1 << 20).unwrap();

        // then: spikes/README.md 표의 크기
        assert_eq!(m.icc_profile.map(|v| v.len()), Some(28));
        assert_eq!(m.exif.map(|v| v.len()), Some(16));
        assert_eq!(m.xmp.map(|v| v.len()), Some(37));
    }

    #[test]
    fn metadata_chunks_over_limit_is_limit_exceeded() {
        // when
        let err = metadata_chunks(STILL, 10).unwrap_err();

        // then
        assert!(matches!(err, WebpError::LimitExceeded(_)), "{err:?}");
    }
}
