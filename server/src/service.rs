//! webp.v1 `WebPService` 구현.
//!
//! - 스트리밍 규칙: docs/design/grpc-api.md §2 (요청: options → 데이터 조각 → half-close, 응답: 헤더 → 조각들)
//! - 상한과 검사 시점: §3, 오류 매핑: §4
//! - libwebp 작업은 blocking 스레드 풀에서 하고, 동시에 작업하는 요청 수를 세마포어로 제한한다(ADR-0012).
//! - 요청마다 추정 메모리를 예산에서 예약한다(ADR-0016, docs/design/grpc-api.md §3.3).

use crate::admission::{
    Admission, DeadlineStream, MemoryReservation, RequestPolicy, Sent, Slot, blocking_send_until, deadline_exceeded,
    send_until,
};
use crate::config::Limits;
use crate::pool::{BufferPool, PoolConfig};
use crate::proto::{self as pb, web_p_service_server::WebPService};
use crate::webp::{self, WebpError};
use bytes::Bytes;
use std::borrow::Cow;
use std::pin::Pin;
use std::sync::Arc;
use tokio::sync::mpsc;
use tokio_stream::Stream;
use tokio_stream::wrappers::ReceiverStream;
use tonic::{Request, Response, Status, Streaming};

/// 응답 채널 깊이. 작게 두어 클라이언트가 느리면 서버 전송이 멈추게 한다(gRPC 흐름 제어와 함께 동작).
const RESPONSE_CHANNEL_DEPTH: usize = 4;

/// 메모리 추정에 쓰는 입력 머리 크기. 캔버스 크기는 RIFF 헤더(12) 뒤 첫 청크에 있다: VP8X 12 + 18 = 30바이트,
/// VP8 12 + 8 + 10 = 30바이트, VP8L 12 + 8 + 5 = 25바이트(RFC 9649 §2.5–2.7). 단위: 바이트.
const HEADER_PEEK_BYTES: usize = 64;

// ADR-0016 추정식 계수(측정값에 여유를 둔 값, research/0007·0012). 단위: 바이트/픽셀.
/// 정지 이미지 디코딩: 입력 크기 + 이 값 × 픽셀 수(측정 약 5 B/px).
const DECODE_STILL_BYTES_PER_PIXEL: u64 = 6;
/// 애니메이션 디코딩: 입력 크기 + 이 값 × 캔버스 픽셀 수(측정 8.6 B/px, 캔버스 버퍼 2개 + 전송 버퍼).
const DECODE_ANIM_BYTES_PER_PIXEL: u64 = 10;
/// 정지 이미지 인코딩: 이 값 × 픽셀 수(측정 31–36 B/px, 입력 버퍼 포함).
const ENCODE_STILL_BYTES_PER_PIXEL: u64 = 40;
/// 애니메이션 인코딩의 캔버스 몫: 이 값 × 캔버스 픽셀 수(측정 50–52 B/px). 프레임 몫은 [`encode_frame_bytes`].
const ENCODE_ANIM_BYTES_PER_PIXEL: u64 = 60;

type ResponseStream<T> = Pin<Box<dyn Stream<Item = Result<T, Status>> + Send + 'static>>;

/// `WebPService` 구현체. 복제해도 상한과 동시 처리 슬롯을 공유한다(UDS·TCP 리스너가 같은 슬롯을 쓴다).
#[derive(Debug, Clone)]
pub struct WebpService {
    limits: Limits,
    admission: Arc<Admission>,
    pool: BufferPool,
}

impl WebpService {
    /// 상한과 동시 처리 수(1 이상)로 서비스를 만든다. 출력 버퍼 풀(ADR-0018)과 대기·시간 상한(ADR-0017)은 기본값이다.
    pub fn new(limits: Limits, max_concurrency: usize) -> Self {
        Self::with_config(
            limits,
            max_concurrency,
            BufferPool::new(PoolConfig::default()),
            RequestPolicy::default(),
        )
    }

    /// [`WebpService::new`]와 같고, 출력 버퍼 풀과 대기·시간 상한을 지정한다. 풀의 유휴 정리는 호출자가 주기적으로
    /// 한다([`BufferPool::evict_expired`]).
    pub fn with_config(limits: Limits, max_concurrency: usize, pool: BufferPool, policy: RequestPolicy) -> Self {
        Self {
            limits,
            admission: Arc::new(Admission::new(max_concurrency, policy, pool.config().max_bytes)),
            pool,
        }
    }
}

/// libwebp 오류를 gRPC status로 바꾼다(docs/design/grpc-api.md §4).
fn to_status(e: WebpError) -> Status {
    match e {
        WebpError::InvalidData(_) | WebpError::InvalidArgument(_) => Status::invalid_argument(e.to_string()),
        WebpError::OutOfMemory(_) | WebpError::LimitExceeded(_) => Status::resource_exhausted(e.to_string()),
        WebpError::Internal(_) => {
            tracing::warn!(error = %e, "libwebp internal error");
            Status::internal(e.to_string())
        }
    }
}

// --- 요청 스트림 수집 ---

/// 요청 스트림 메시지 하나의 종류.
enum Part<O> {
    Options(O),
    Data(Bytes),
}

/// options 1개 + 데이터 조각들로 이루어진 요청 스트림의 메시지.
trait InputMessage {
    type Options;
    fn into_part(self) -> Option<Part<Self::Options>>;
}

macro_rules! impl_input_message {
    ($msg:ty, $payload:path, $options:ty, $data:ident) => {
        impl InputMessage for $msg {
            type Options = $options;
            fn into_part(self) -> Option<Part<$options>> {
                use $payload as P;
                self.payload.map(|p| match p {
                    P::Options(o) => Part::Options(o),
                    P::$data(d) => Part::Data(d),
                })
            }
        }
    };
}

impl_input_message!(
    pb::GetFeaturesRequest,
    pb::get_features_request::Payload,
    pb::GetFeaturesOptions,
    WebpData
);
impl_input_message!(
    pb::DecodeRequest,
    pb::decode_request::Payload,
    pb::DecodeOptions,
    WebpData
);
impl_input_message!(
    pb::DecodeAnimationRequest,
    pb::decode_animation_request::Payload,
    pb::DecodeAnimationOptions,
    WebpData
);
impl_input_message!(
    pb::EncodeRequest,
    pb::encode_request::Payload,
    pb::EncodeOptions,
    Pixels
);

/// 요청 스트림을 끝까지 받아 options와 데이터를 모은다.
///
/// `expected_len`은 options를 검증하고 뒤따를 데이터의 바이트 수를 돌려준다. `estimate`는 이 요청의 추정 메모리를
/// 돌려준다(ADR-0016). `peek_header`가 참이면 입력 머리(최소 [`HEADER_PEEK_BYTES`])를 먼저 받아 `estimate`에 넘기고,
/// 거짓이면 빈 슬라이스를 넘긴다. 디코딩 추정에는 헤더의 캔버스 크기가 필요하기 때문이다. 머리를 읽기 전에 대기 자리를
/// 잡으므로 머리를 들고 기다리는 요청 수도 대기 상한으로 묶이고, 머리는 요청 시간 상한까지만 기다린다.
///
/// 추정치를 예약하고 슬롯을 잡은 **뒤에** 입력 버퍼를 할당한다. 그래서 큰 입력(인코딩 픽셀은 최대 수백 MiB)도 예산
/// 안에서만 받는다. 슬롯과 예약은 돌려받은 쪽이 응답 전송이 끝날 때까지 들고 있는다. 기다리는 동안 클라이언트가
/// 취소하면 tonic이 이 future를 버리므로 대기도 함께 끝난다. 대기 상한을 넘으면 기다리지 않고 거부하고, 슬롯을 잡은
/// 뒤의 입력 수신은 요청 시간 상한까지만 기다린다(ADR-0017). 조각 크기·순서·합계 검사는 docs/design/grpc-api.md §2.2를
/// 따른다.
async fn collect_input<M: InputMessage>(
    stream: &mut Streaming<M>,
    max_chunk_bytes: u32,
    admission: &Admission,
    expected_len: impl FnOnce(&M::Options) -> Result<u64, Status>,
    peek_header: bool,
    estimate: impl FnOnce(&M::Options, &[u8]) -> Result<u64, Status>,
) -> Result<(M::Options, Vec<u8>, Slot), Status> {
    // 첫 메시지는 options여야 한다.
    let first = stream
        .message()
        .await?
        .ok_or_else(|| Status::invalid_argument("empty request stream: the first message must be options"))?;
    let Some(Part::Options(options)) = first.into_part() else {
        return Err(Status::invalid_argument("the first message must be options"));
    };
    let expected = expected_len(&options)?;
    let expected = usize::try_from(expected)
        .map_err(|_| Status::resource_exhausted(format!("{expected} bytes do not fit in memory")))?;

    // ADR-0016: 디코딩 추정용 머리를 대기 자리를 잡은 채 받는다. 머리는 조각 몇 개(대개 하나, 최대 max_chunk_bytes)다.
    let mut head: Vec<Bytes> = Vec::new();
    let mut received = 0usize;
    let mut ended = false;
    let mut place = None;
    if peek_header {
        place = Some(admission.enqueue()?);
        let deadline = tokio::time::Instant::now() + admission.max_request();
        while received < HEADER_PEEK_BYTES.min(expected) {
            let Some(message) = receive_until(stream, deadline).await? else {
                ended = true;
                break;
            };
            let chunk = data_part(message)?;
            check_chunk(chunk.len(), max_chunk_bytes, received, expected)?;
            received += chunk.len();
            head.push(chunk);
        }
    }
    let estimate = {
        let prefix: Cow<'_, [u8]> = match head.as_slice() {
            [] => Cow::Borrowed(&[]),
            [one] => Cow::Borrowed(one),
            many => Cow::Owned(many.concat()),
        };
        estimate(&options, &prefix)?
    };
    let slot = match place {
        Some(place) => admission.acquire_queued(place, estimate).await?,
        None => admission.acquire(estimate).await?,
    };

    // 선언된 크기만큼만 할당하고 조각을 모은다. 크기와 순서가 어긋나면 클라이언트 버그이므로 INVALID_ARGUMENT.
    let mut buf = Vec::new();
    buf.try_reserve_exact(expected)
        .map_err(|_| Status::resource_exhausted(format!("cannot allocate {expected} bytes for the input")))?;
    for chunk in head {
        buf.extend_from_slice(&chunk);
    }
    if !ended {
        while let Some(message) = receive_until(stream, slot.deadline()).await? {
            let chunk = data_part(message)?;
            check_chunk(chunk.len(), max_chunk_bytes, buf.len(), expected)?;
            buf.extend_from_slice(&chunk);
        }
    }
    if buf.len() != expected {
        return Err(Status::invalid_argument(format!(
            "declared {expected} bytes but received {}",
            buf.len()
        )));
    }
    Ok((options, buf, slot))
}

/// options 뒤의 메시지에서 데이터 조각을 꺼낸다.
fn data_part<M: InputMessage>(message: M) -> Result<Bytes, Status> {
    match message.into_part() {
        Some(Part::Data(chunk)) => Ok(chunk),
        Some(Part::Options(_)) => Err(Status::invalid_argument(
            "options must be sent once, as the first message",
        )),
        None => Err(Status::invalid_argument("request message without payload")),
    }
}

/// 조각 하나의 크기와 누적 합계를 검사한다(docs/design/grpc-api.md §2.2).
fn check_chunk(len: usize, max_chunk_bytes: u32, received: usize, expected: usize) -> Result<(), Status> {
    if len == 0 || len > max_chunk_bytes as usize {
        return Err(Status::invalid_argument(format!(
            "chunk size must be 1..={max_chunk_bytes} bytes, got {len}"
        )));
    }
    if received + len > expected {
        return Err(Status::invalid_argument(format!(
            "received more than the declared {expected} bytes"
        )));
    }
    Ok(())
}

/// 디코딩 추정 메모리(ADR-0016): 입력 크기 + 계수 × 캔버스 픽셀 수. 애니메이션은 캔버스 버퍼를 2개 쓴다.
fn decode_estimate(input_bytes: u64, features: &webp::Features) -> u64 {
    let per_pixel = if features.has_animation {
        DECODE_ANIM_BYTES_PER_PIXEL
    } else {
        DECODE_STILL_BYTES_PER_PIXEL
    };
    input_bytes.saturating_add(per_pixel.saturating_mul(features.pixel_count()))
}

/// 입력 머리로 디코딩 추정 메모리를 센다. 캔버스가 상한을 넘으면 나머지를 받기 전에 거부한다. 머리에서 헤더를 읽지
/// 못하면(손상, 아주 작은 조각) 입력 크기만 예약한다. 그때는 입력을 다 받은 뒤 [`Slot::ensure_reserved`]로 다시 세고,
/// 헤더 오류도 지금처럼 그때 낸다.
fn decode_head_estimate(input_bytes: u64, head: &[u8], max_pixels: u64) -> Result<u64, Status> {
    match webp::features(head) {
        Ok(features) => {
            check_canvas(&features, max_pixels)?;
            Ok(decode_estimate(input_bytes, &features))
        }
        Err(_) => Ok(input_bytes),
    }
}

/// 애니메이션 인코딩의 프레임 하나 몫(ADR-0016): lossless 1 B, lossy 0.25 B × 캔버스 픽셀 수. 인코더가 프레임별 압축
/// 결과를 끝까지 모아 두기 때문으로 본다(추정, research/0012).
fn encode_frame_bytes(pixel_count: u64, lossless: bool) -> u64 {
    if lossless { pixel_count } else { pixel_count.div_ceil(4) }
}

/// 응답 버퍼와 그 메모리 예약. 버퍼(와 모든 조각)가 해제된 뒤에 예약이 풀린다(필드 순서, ADR-0016).
struct Reserved {
    data: Vec<u8>,
    _memory: MemoryReservation,
}

impl AsRef<[u8]> for Reserved {
    fn as_ref(&self) -> &[u8] {
        &self.data
    }
}

/// `data`를 메모리 예약과 함께 [`Bytes`]로 감싼다.
fn reserved_bytes(data: Vec<u8>, memory: MemoryReservation) -> Bytes {
    Bytes::from_owner(Reserved { data, _memory: memory })
}

/// 다음 요청 메시지를 마감까지만 기다린다(느린 업로드가 슬롯을 쥐지 못하게, ADR-0017).
async fn receive_until<M>(stream: &mut Streaming<M>, deadline: tokio::time::Instant) -> Result<Option<M>, Status> {
    tokio::time::timeout_at(deadline, stream.message())
        .await
        .map_err(|_| deadline_exceeded())?
}

/// 합성 픽셀 수(캔버스 × 프레임 수)가 상한을 넘으면 거부한다(ADR-0017 출력량 폭탄).
fn check_composited(canvas_pixels: u64, frames: u64, limits: &Limits) -> Result<(), Status> {
    let total = canvas_pixels.saturating_mul(frames);
    if total > limits.max_composited_pixels {
        return Err(Status::resource_exhausted(format!(
            "{frames} frames of a {canvas_pixels}-pixel canvas = {total} composited pixels exceeds \
             max_composited_pixels {}",
            limits.max_composited_pixels
        )));
    }
    Ok(())
}

/// 압축 입력 선언 크기를 검사한다.
fn check_input_bytes(declared: u64, limits: &Limits) -> Result<u64, Status> {
    if declared == 0 {
        return Err(Status::invalid_argument("input_bytes must be >= 1"));
    }
    if declared > limits.max_input_bytes {
        return Err(Status::resource_exhausted(format!(
            "input of {declared} bytes exceeds max_input_bytes {}",
            limits.max_input_bytes
        )));
    }
    Ok(declared)
}

/// 요청한 픽셀 포맷을 검사한다. v1은 RGBA8만 지원한다(ADR-0004).
fn check_pixel_format(value: i32) -> Result<(), Status> {
    match pb::PixelFormat::try_from(value) {
        Ok(pb::PixelFormat::Unspecified | pb::PixelFormat::Rgba8) => Ok(()),
        _ => Err(Status::invalid_argument(format!("unsupported pixel_format {value}"))),
    }
}

/// 서버 상한과 클라이언트 상한 중 작은 값(docs/design/grpc-api.md §3).
fn effective_max_pixels(limits: &Limits, client_max: Option<u64>) -> u64 {
    client_max
        .filter(|m| *m > 0)
        .map_or(limits.max_pixels, |m| m.min(limits.max_pixels))
}

/// 캔버스가 상한을 넘으면 픽셀 버퍼를 할당하기 전에 거부한다(decompression bomb 방어).
fn check_canvas(features: &webp::Features, max_pixels: u64) -> Result<(), Status> {
    if features.pixel_count() > max_pixels {
        return Err(Status::resource_exhausted(format!(
            "canvas {}x{} = {} pixels exceeds max_pixels {max_pixels}",
            features.width,
            features.height,
            features.pixel_count()
        )));
    }
    Ok(())
}

// --- 응답 전송 ---

/// libwebp 작업을 blocking 스레드 풀에서 실행한다. 작업 안의 패닉은 INTERNAL로 바꾼다.
async fn run_blocking<R: Send + 'static>(f: impl FnOnce() -> Result<R, Status> + Send + 'static) -> Result<R, Status> {
    tokio::task::spawn_blocking(f)
        .await
        .map_err(|e| Status::internal(format!("worker task failed: {e}")))?
}

/// `data`를 `chunk` 바이트 이하 조각으로 나눠 마감까지 보낸다. 복사 없이 `Bytes`를 자른다.
async fn send_chunks<T>(
    tx: &mpsc::Sender<Result<T, Status>>,
    data: Bytes,
    chunk: usize,
    deadline: tokio::time::Instant,
    wrap: impl Fn(Bytes) -> T,
) -> Sent {
    let mut start = 0;
    while start < data.len() {
        let end = (start + chunk).min(data.len());
        let sent = send_until(tx, Ok(wrap(data.slice(start..end))), deadline).await;
        if sent != Sent::Ok {
            return sent;
        }
        start = end;
    }
    Sent::Ok
}

/// blocking 스레드에서 `data`를 `chunk` 바이트 이하 조각으로 복사해 보낸다. 채널이 차면 마감까지 기다린다(흐름 제어).
fn blocking_send_chunks<T>(
    tx: &mpsc::Sender<Result<T, Status>>,
    data: &[u8],
    chunk: usize,
    deadline: tokio::time::Instant,
    wrap: impl Fn(Bytes) -> T,
) -> Sent {
    for c in data.chunks(chunk) {
        let sent = blocking_send_until(tx, Ok(wrap(Bytes::copy_from_slice(c))), deadline);
        if sent != Sent::Ok {
            return sent;
        }
    }
    Sent::Ok
}

/// blocking 스레드에서 오류를 응답 스트림에 알린다. 클라이언트가 읽지 않으면 마감까지만 기다린다.
fn blocking_report<T>(tx: &mpsc::Sender<Result<T, Status>>, status: Status, deadline: tokio::time::Instant) {
    let _ = blocking_send_until(tx, Err(status), deadline);
}

/// 전송 결과를 처리 결과로 바꾼다. 마감을 넘겼으면 `DEADLINE_EXCEEDED`, 클라이언트가 떠났으면 조용히 끝낸다.
fn sent_result(sent: Sent) -> Result<bool, Status> {
    match sent {
        Sent::Ok => Ok(true),
        Sent::Gone => Ok(false),
        Sent::Expired => Err(deadline_exceeded()),
    }
}

// --- proto 변환 ---

fn to_pb_compression(format: webp::Format, animated: bool) -> pb::Compression {
    match format {
        webp::Format::Lossy => pb::Compression::Lossy,
        webp::Format::Lossless => pb::Compression::Lossless,
        webp::Format::UndefinedOrMixed if animated => pb::Compression::Mixed,
        webp::Format::UndefinedOrMixed => pb::Compression::Unspecified,
    }
}

fn to_pb_features(info: &webp::ContainerInfo) -> pb::ImageFeatures {
    let f = &info.features;
    let animation = f.has_animation.then(|| pb::AnimationInfo {
        loop_count: info.loop_count,
        background_color_argb: info.background_color_argb,
        frames: info
            .frames
            .iter()
            .map(|fr| pb::FrameInfo {
                x_offset: fr.x_offset,
                y_offset: fr.y_offset,
                width: fr.width,
                height: fr.height,
                duration_ms: fr.duration_ms,
                blend: if fr.alpha_blend {
                    pb::BlendMethod::AlphaBlend
                } else {
                    pb::BlendMethod::NoBlend
                } as i32,
                dispose: if fr.dispose_to_background {
                    pb::DisposeMethod::Background
                } else {
                    pb::DisposeMethod::None
                } as i32,
                has_alpha: fr.has_alpha,
                compression: to_pb_compression(fr.format, false) as i32,
            })
            .collect(),
    });
    pb::ImageFeatures {
        width: f.width,
        height: f.height,
        has_alpha: f.has_alpha,
        has_animation: f.has_animation,
        compression: to_pb_compression(info.overall_format(), f.has_animation) as i32,
        frame_count: info.frames.len() as u32,
        animation,
    }
}

fn to_pb_metadata(m: webp::MetadataChunks) -> pb::Metadata {
    pb::Metadata {
        icc_profile: m.icc_profile.map(Bytes::from),
        exif: m.exif.map(Bytes::from),
        xmp: m.xmp.map(Bytes::from),
    }
}

fn to_webp_metadata(m: Option<&pb::Metadata>) -> webp::MetadataChunks {
    m.map_or_else(webp::MetadataChunks::default, |m| webp::MetadataChunks {
        icc_profile: m.icc_profile.as_ref().map(|b| b.to_vec()),
        exif: m.exif.as_ref().map(|b| b.to_vec()),
        xmp: m.xmp.as_ref().map(|b| b.to_vec()),
    })
}

/// 넣을 메타데이터 합계가 상한 안인지 검사한다(docs/design/grpc-api.md §3).
fn check_metadata_len(m: Option<&pb::Metadata>, limits: &Limits) -> Result<(), Status> {
    let len: u64 = m.map_or(0, |m| {
        [&m.icc_profile, &m.exif, &m.xmp]
            .iter()
            .map(|c| c.as_ref().map_or(0, |b| b.len() as u64))
            .sum()
    });
    if len > limits.max_metadata_bytes {
        return Err(Status::resource_exhausted(format!(
            "metadata of {len} bytes exceeds max_metadata_bytes {}",
            limits.max_metadata_bytes
        )));
    }
    Ok(())
}

fn to_webp_params(p: Option<&pb::EncodeParams>) -> webp::EncodeParams {
    p.map_or_else(webp::EncodeParams::default, |p| webp::EncodeParams {
        lossless: p.lossless,
        quality: p.quality,
        method: p.method,
    })
}

fn frame_header(
    frame_index: u32,
    width: u32,
    height: u32,
    pixel_bytes: usize,
    has_alpha: bool,
    duration_ms: u32,
) -> pb::FrameHeader {
    pb::FrameHeader {
        frame_index,
        width,
        height,
        pixel_format: pb::PixelFormat::Rgba8 as i32,
        pixel_bytes: pixel_bytes as u64,
        has_alpha,
        duration_ms,
    }
}

/// 프레임 하나가 캔버스 크기만큼 다 왔는지 검사한다.
fn check_frame_complete(buf: &[u8], frame_bytes: usize) -> Result<(), Status> {
    if buf.len() != frame_bytes {
        return Err(Status::invalid_argument(format!(
            "frame has {} bytes, expected {frame_bytes}",
            buf.len()
        )));
    }
    Ok(())
}

/// 애니메이션의 `frame_index`번째 합성 프레임을 보낸다. WebPAnimDecoder는 순차 합성만 지원하므로 0번부터 합성한다
/// (docs/design/grpc-api.md §2.4). 합성량(캔버스 × (frame_index + 1))이 상한을 넘으면 디코딩 전에 거부하고, 프레임
/// 사이와 전송에서 요청 시간 상한을 지킨다(ADR-0017). blocking 스레드에서 부른다.
fn send_animation_frame(
    data: &[u8],
    frame_index: u32,
    limits: &Limits,
    slot: &Slot,
    tx: &mpsc::Sender<Result<pb::DecodeResponse, Status>>,
) -> Result<(), Status> {
    use pb::decode_response::Payload;
    let info = webp::container_info(data, limits.max_frames).map_err(to_status)?;
    let count = info.frames.len() as u32;
    if frame_index >= count {
        return Err(Status::out_of_range(format!(
            "frame_index {frame_index} is out of range ({count} frames)"
        )));
    }
    check_composited(info.features.pixel_count(), u64::from(frame_index) + 1, limits)?;
    let mut decoder = webp::AnimDecoder::new(data).map_err(to_status)?;
    let (width, height) = (decoder.info().canvas_width, decoder.info().canvas_height);
    let mut index = 0;
    loop {
        slot.check()?;
        let Some((canvas, _)) = decoder.next_frame().map_err(to_status)? else {
            break;
        };
        if index == frame_index {
            let duration = info.frames[index as usize].duration_ms;
            let header = frame_header(index, width, height, canvas.len(), true, duration);
            let header = pb::DecodeResponse {
                payload: Some(Payload::Header(header)),
            };
            if sent_result(blocking_send_until(tx, Ok(header), slot.deadline()))? {
                sent_result(blocking_send_chunks(
                    tx,
                    canvas,
                    limits.max_chunk_bytes as usize,
                    slot.deadline(),
                    |b| pb::DecodeResponse {
                        payload: Some(Payload::Pixels(b)),
                    },
                ))?;
            }
            return Ok(());
        }
        index += 1;
    }
    Err(Status::invalid_argument(format!(
        "animation ended after {index} of {count} frames"
    )))
}

/// DecodeAnimation 응답을 보낸다: 특성 1개 → (프레임 헤더 → 픽셀 조각들) × 프레임 수. 정지 이미지는 프레임 1개.
/// 합성량(캔버스 × 프레임 수)이 상한을 넘으면 아무것도 보내기 전에 거부하고, 프레임 사이와 전송에서 요청 시간 상한을
/// 지킨다(ADR-0017). blocking 스레드에서 부른다. 클라이언트가 떠나면 조용히 끝낸다.
fn stream_animation(
    data: &[u8],
    features: &webp::Features,
    limits: &Limits,
    slot: &Slot,
    tx: &mpsc::Sender<Result<pb::DecodeAnimationResponse, Status>>,
) -> Result<(), Status> {
    use pb::decode_animation_response::Payload;
    let info = webp::container_info(data, limits.max_frames).map_err(to_status)?;
    check_composited(features.pixel_count(), info.frames.len().max(1) as u64, limits)?;
    let chunk = limits.max_chunk_bytes as usize;
    let deadline = slot.deadline();
    let send = |p: Payload| {
        sent_result(blocking_send_until(
            tx,
            Ok(pb::DecodeAnimationResponse { payload: Some(p) }),
            deadline,
        ))
    };
    let send_pixels = |pixels: &[u8]| {
        sent_result(blocking_send_chunks(tx, pixels, chunk, deadline, |b| {
            pb::DecodeAnimationResponse {
                payload: Some(Payload::Pixels(b)),
            }
        }))
    };
    if !send(Payload::Features(to_pb_features(&info)))? {
        return Ok(());
    }
    if !features.has_animation {
        let pixels = webp::decode_still_rgba(data, features).map_err(to_status)?;
        let header = frame_header(0, features.width, features.height, pixels.len(), features.has_alpha, 0);
        if send(Payload::Frame(header))? {
            send_pixels(&pixels)?;
        }
        return Ok(());
    }
    let mut decoder = webp::AnimDecoder::new(data).map_err(to_status)?;
    let (width, height) = (decoder.info().canvas_width, decoder.info().canvas_height);
    let mut index = 0u32;
    loop {
        slot.check()?;
        let Some((canvas, _)) = decoder.next_frame().map_err(to_status)? else {
            break;
        };
        let duration = info.frames.get(index as usize).map_or(0, |f| f.duration_ms);
        let header = frame_header(index, width, height, canvas.len(), true, duration);
        if !send(Payload::Frame(header))? || !send_pixels(canvas)? {
            return Ok(());
        }
        index += 1;
    }
    Ok(())
}

#[tonic::async_trait]
impl WebPService for WebpService {
    type DecodeStream = ResponseStream<pb::DecodeResponse>;
    type DecodeAnimationStream = ResponseStream<pb::DecodeAnimationResponse>;
    type EncodeStream = ResponseStream<pb::EncodeResponse>;
    type EncodeAnimationStream = ResponseStream<pb::EncodeAnimationResponse>;

    async fn get_info(&self, _request: Request<pb::GetInfoRequest>) -> Result<Response<pb::GetInfoResponse>, Status> {
        let v = webp::versions();
        let l = self.limits;
        Ok(Response::new(pb::GetInfoResponse {
            server_version: env!("CARGO_PKG_VERSION").to_string(),
            libwebp: Some(pb::LibwebpVersions {
                decoder: v.decoder,
                encoder: v.encoder,
                demux: v.demux,
                mux: v.mux,
            }),
            limits: Some(pb::Limits {
                max_input_bytes: l.max_input_bytes,
                max_pixels: l.max_pixels,
                max_frames: l.max_frames,
                max_metadata_bytes: l.max_metadata_bytes,
                max_chunk_bytes: l.max_chunk_bytes,
                max_composited_pixels: l.max_composited_pixels,
            }),
            pixel_formats: vec![pb::PixelFormat::Rgba8 as i32],
        }))
    }

    async fn get_features(
        &self,
        request: Request<Streaming<pb::GetFeaturesRequest>>,
    ) -> Result<Response<pb::GetFeaturesResponse>, Status> {
        let limits = self.limits;
        let mut stream = request.into_inner();
        let (options, data, slot) = collect_input(
            &mut stream,
            limits.max_chunk_bytes,
            &self.admission,
            |o: &pb::GetFeaturesOptions| check_input_bytes(o.input_bytes, &limits),
            false,
            // ADR-0016: 헤더와 청크 목록만 읽으므로 입력 크기만 예약한다.
            |o: &pb::GetFeaturesOptions, _| Ok(o.input_bytes),
        )
        .await?;

        // 헤더와 청크 목록만 읽는다(픽셀 디코딩 없음). 입력 버퍼를 들고 있는 동안 슬롯을 유지한다.
        let response = run_blocking(move || {
            let _slot = slot;
            let info = webp::container_info(&data, limits.max_frames).map_err(to_status)?;
            let metadata = if options.include_metadata {
                Some(to_pb_metadata(
                    webp::metadata_chunks(&data, limits.max_metadata_bytes).map_err(to_status)?,
                ))
            } else {
                None
            };
            Ok(pb::GetFeaturesResponse {
                features: Some(to_pb_features(&info)),
                metadata,
            })
        })
        .await?;
        Ok(Response::new(response))
    }

    async fn decode(
        &self,
        request: Request<Streaming<pb::DecodeRequest>>,
    ) -> Result<Response<Self::DecodeStream>, Status> {
        let limits = self.limits;
        let mut stream = request.into_inner();
        let (options, data, mut slot) = collect_input(
            &mut stream,
            limits.max_chunk_bytes,
            &self.admission,
            |o: &pb::DecodeOptions| {
                check_pixel_format(o.pixel_format)?;
                check_input_bytes(o.input_bytes, &limits)
            },
            true,
            |o: &pb::DecodeOptions, head| {
                decode_head_estimate(o.input_bytes, head, effective_max_pixels(&limits, o.max_pixels))
            },
        )
        .await?;
        let deadline = slot.deadline();

        // 헤더만 읽어(빠름) 상한을 먼저 검사한다. 넘으면 버퍼를 할당하지 않고 거부한다.
        let features = webp::features(&data).map_err(to_status)?;
        check_canvas(&features, effective_max_pixels(&limits, options.max_pixels))?;
        // ADR-0016: 머리로 센 추정치가 모자라면(머리에서 헤더를 못 읽은 경우) 기다리지 않고 더 예약한다.
        slot.ensure_reserved(decode_estimate(data.len() as u64, &features))?;
        if features.has_animation {
            // 애니메이션: frame_index번째 합성 프레임을 디코더 버퍼에서 바로 보낸다.
            let (tx, rx) = mpsc::channel(RESPONSE_CHANNEL_DEPTH);
            let frame_index = options.frame_index;
            tokio::task::spawn_blocking(move || {
                if let Err(status) = send_animation_frame(&data, frame_index, &limits, &slot, &tx) {
                    blocking_report(&tx, status, deadline);
                }
            });
            return Ok(Response::new(Box::pin(DeadlineStream::new(
                ReceiverStream::new(rx),
                deadline,
            ))));
        }
        if options.frame_index != 0 {
            return Err(Status::out_of_range(format!(
                "frame_index {} is out of range for a still image (1 frame)",
                options.frame_index
            )));
        }

        // 디코딩하고 헤더 → 픽셀 조각 순서로 보낸다. 조각을 다 넘길 때까지 슬롯을 유지한다.
        // 출력 버퍼는 풀에서 꺼내고, 전송이 끝나 마지막 조각이 해제되면 풀로 돌아간다(ADR-0018). 메모리 예약은 출력
        // 버퍼에 붙여 버퍼가 풀로 돌아간 뒤에 놓는다(ADR-0016).
        let (tx, rx) = mpsc::channel(RESPONSE_CHANNEL_DEPTH);
        let pool = self.pool.clone();
        tokio::spawn(async move {
            let mut slot = slot;
            let decode_pool = pool.clone();
            let decoded = run_blocking(move || {
                let len = features.pixel_count() as usize * 4;
                let mut buf = decode_pool.take(len);
                match webp::decode_still_rgba_into(&data, &features, &mut buf) {
                    Ok(()) => Ok(buf),
                    Err(e) => {
                        decode_pool.give(buf);
                        Err(to_status(e))
                    }
                }
            })
            .await;
            match decoded {
                Ok(pixels) => {
                    let header = frame_header(0, features.width, features.height, pixels.len(), features.has_alpha, 0);
                    let header = pb::DecodeResponse {
                        payload: Some(pb::decode_response::Payload::Header(header)),
                    };
                    if send_until(&tx, Ok(header), deadline).await == Sent::Ok {
                        let pixels = pool.wrap_with(pixels, slot.take_memory());
                        send_chunks(&tx, pixels, limits.max_chunk_bytes as usize, deadline, |b| {
                            pb::DecodeResponse {
                                payload: Some(pb::decode_response::Payload::Pixels(b)),
                            }
                        })
                        .await;
                    }
                }
                Err(status) => {
                    let _ = send_until(&tx, Err(status), deadline).await;
                }
            }
        });
        Ok(Response::new(Box::pin(DeadlineStream::new(
            ReceiverStream::new(rx),
            deadline,
        ))))
    }

    async fn decode_animation(
        &self,
        request: Request<Streaming<pb::DecodeAnimationRequest>>,
    ) -> Result<Response<Self::DecodeAnimationStream>, Status> {
        let limits = self.limits;
        let mut stream = request.into_inner();
        let (options, data, mut slot) = collect_input(
            &mut stream,
            limits.max_chunk_bytes,
            &self.admission,
            |o: &pb::DecodeAnimationOptions| {
                check_pixel_format(o.pixel_format)?;
                check_input_bytes(o.input_bytes, &limits)
            },
            true,
            |o: &pb::DecodeAnimationOptions, head| {
                decode_head_estimate(o.input_bytes, head, effective_max_pixels(&limits, o.max_pixels))
            },
        )
        .await?;
        let deadline = slot.deadline();
        let features = webp::features(&data).map_err(to_status)?;
        check_canvas(&features, effective_max_pixels(&limits, options.max_pixels))?;
        slot.ensure_reserved(decode_estimate(data.len() as u64, &features))?;

        // 특성 → (프레임 헤더 → 픽셀 조각들) × 프레임 수. 채널이 차면 디코딩이 멈추고, 클라이언트가 떠나면 끝낸다.
        let (tx, rx) = mpsc::channel(RESPONSE_CHANNEL_DEPTH);
        tokio::task::spawn_blocking(move || {
            if let Err(status) = stream_animation(&data, &features, &limits, &slot, &tx) {
                blocking_report(&tx, status, deadline);
            }
        });
        Ok(Response::new(Box::pin(DeadlineStream::new(
            ReceiverStream::new(rx),
            deadline,
        ))))
    }

    async fn encode(
        &self,
        request: Request<Streaming<pb::EncodeRequest>>,
    ) -> Result<Response<Self::EncodeStream>, Status> {
        let limits = self.limits;
        let mut stream = request.into_inner();

        // options를 먼저 검증하고(크기, 상한, 품질) 그 크기만큼 픽셀을 받는다.
        let (options, pixels, slot) = collect_input(
            &mut stream,
            limits.max_chunk_bytes,
            &self.admission,
            |o: &pb::EncodeOptions| {
                check_pixel_format(o.pixel_format)?;
                webp::check_dimensions(o.width, o.height).map_err(to_status)?;
                let pixel_count = u64::from(o.width) * u64::from(o.height);
                if pixel_count > limits.max_pixels {
                    return Err(Status::resource_exhausted(format!(
                        "image {}x{} = {pixel_count} pixels exceeds max_pixels {}",
                        o.width, o.height, limits.max_pixels
                    )));
                }
                check_metadata_len(o.metadata.as_ref(), &limits)?;
                webp::encoder_config(&to_webp_params(o.params.as_ref())).map_err(to_status)?;
                Ok(pixel_count * webp::BYTES_PER_PIXEL as u64)
            },
            false,
            // ADR-0016: 인코더 메모리(입력 버퍼 포함).
            |o: &pb::EncodeOptions, _| Ok(ENCODE_STILL_BYTES_PER_PIXEL * u64::from(o.width) * u64::from(o.height)),
        )
        .await?;

        // 인코딩하고 헤더 → WebP 조각 순서로 보낸다. 조각을 다 넘길 때까지 슬롯을 유지하고, 메모리 예약은 결과 버퍼가
        // 해제될 때 놓는다(ADR-0016).
        let deadline = slot.deadline();
        let (tx, rx) = mpsc::channel(RESPONSE_CHANNEL_DEPTH);
        tokio::spawn(async move {
            let mut slot = slot;
            let params = to_webp_params(options.params.as_ref());
            let metadata = to_webp_metadata(options.metadata.as_ref());
            let result = run_blocking(move || {
                let encoded =
                    webp::encode_still_rgba(&pixels, options.width, options.height, &params).map_err(to_status)?;
                webp::add_metadata(encoded, &metadata).map_err(to_status)
            })
            .await;
            match result {
                Ok(encoded) => {
                    let header = pb::EncodedHeader {
                        webp_bytes: encoded.len() as u64,
                    };
                    let header = pb::EncodeResponse {
                        payload: Some(pb::encode_response::Payload::Header(header)),
                    };
                    if send_until(&tx, Ok(header), deadline).await == Sent::Ok {
                        send_chunks(
                            &tx,
                            reserved_bytes(encoded, slot.take_memory()),
                            limits.max_chunk_bytes as usize,
                            deadline,
                            |b| pb::EncodeResponse {
                                payload: Some(pb::encode_response::Payload::WebpData(b)),
                            },
                        )
                        .await;
                    }
                }
                Err(status) => {
                    let _ = send_until(&tx, Err(status), deadline).await;
                }
            }
        });
        Ok(Response::new(Box::pin(DeadlineStream::new(
            ReceiverStream::new(rx),
            deadline,
        ))))
    }

    async fn encode_animation(
        &self,
        request: Request<Streaming<pb::EncodeAnimationRequest>>,
    ) -> Result<Response<Self::EncodeAnimationStream>, Status> {
        use pb::encode_animation_request::Payload;
        let limits = self.limits;
        let mut stream = request.into_inner();

        // 첫 메시지(options)를 검증한다. 프레임 수는 미리 알 수 없으므로 받으면서 센다(proto 주석).
        let first = stream
            .message()
            .await?
            .ok_or_else(|| Status::invalid_argument("empty request stream: the first message must be options"))?;
        let Some(Payload::Options(options)) = first.payload else {
            return Err(Status::invalid_argument("the first message must be options"));
        };
        check_pixel_format(options.pixel_format)?;
        let (width, height) = (options.canvas_width, options.canvas_height);
        webp::check_dimensions(width, height).map_err(to_status)?;
        let pixel_count = u64::from(width) * u64::from(height);
        if pixel_count > limits.max_pixels {
            return Err(Status::resource_exhausted(format!(
                "canvas {width}x{height} = {pixel_count} pixels exceeds max_pixels {}",
                limits.max_pixels
            )));
        }
        check_metadata_len(options.metadata.as_ref(), &limits)?;
        let params = to_webp_params(options.params.as_ref());
        webp::encoder_config(&params).map_err(to_status)?;
        let frame_bytes = (pixel_count * webp::BYTES_PER_PIXEL as u64) as usize;
        // ADR-0016: 시작할 때 캔버스 몫을 예약하고, 프레임을 받을 때마다 프레임 몫을 기다리지 않고 더 예약한다.
        let canvas_estimate = ENCODE_ANIM_BYTES_PER_PIXEL * pixel_count;
        let frame_estimate = encode_frame_bytes(pixel_count, params.lossless);
        let mut slot = self.admission.acquire(canvas_estimate).await?;
        let deadline = slot.deadline();

        // 인코더는 blocking 작업 스레드가 가진다. 완성된 프레임을 받는 대로 넘긴다(입력 전체를 모으지 않는다).
        let (frame_tx, mut frame_rx) = mpsc::channel::<(u32, Vec<u8>)>(1);
        let metadata = to_webp_metadata(options.metadata.as_ref());
        let (loop_count, background) = (options.loop_count, options.background_color_argb);
        let worker = tokio::task::spawn_blocking(move || -> Result<Vec<u8>, Status> {
            let mut encoder =
                webp::AnimEncoder::new(width, height, loop_count, background, &params).map_err(to_status)?;
            while let Some((duration_ms, rgba)) = frame_rx.blocking_recv() {
                if tokio::time::Instant::now() >= deadline {
                    return Err(deadline_exceeded());
                }
                encoder.add_frame(&rgba, duration_ms).map_err(to_status)?;
            }
            if tokio::time::Instant::now() >= deadline {
                return Err(deadline_exceeded());
            }
            let encoded = encoder.finish().map_err(to_status)?;
            webp::add_metadata(encoded, &metadata).map_err(to_status)
        });

        // 요청 스트림: (frame → pixels 조각들) × N. 순서와 프레임 크기를 검사한다.
        let received: Result<(), Status> = async {
            let mut current: Option<(u32, Vec<u8>)> = None;
            let mut frames = 0u32;
            loop {
                let message = receive_until(&mut stream, deadline).await?;
                let payload = match message {
                    Some(m) => m.payload,
                    None => break,
                };
                match payload {
                    Some(Payload::Frame(frame)) => {
                        if let Some(done) = current.take() {
                            check_frame_complete(&done.1, frame_bytes)?;
                            if frame_tx.send(done).await.is_err() {
                                return Ok(()); // 작업 스레드가 먼저 실패했다. 결과에서 오류를 꺼낸다.
                            }
                        }
                        frames += 1;
                        if frames > limits.max_frames {
                            return Err(Status::resource_exhausted(format!(
                                "more than max_frames {} frames",
                                limits.max_frames
                            )));
                        }
                        // ADR-0017: 프레임 수를 미리 알 수 없으므로 받을 때마다 합성량을 센다.
                        check_composited(pixel_count, u64::from(frames), &limits)?;
                        // ADR-0016: 예산이 모자라면 기다리지 않고 끝낸다(쥔 채 기다리면 교착).
                        slot.ensure_reserved(canvas_estimate + frame_estimate * u64::from(frames))?;
                        let mut buf = Vec::new();
                        buf.try_reserve_exact(frame_bytes)
                            .map_err(|_| Status::resource_exhausted("cannot allocate a frame buffer"))?;
                        current = Some((frame.duration_ms, buf));
                    }
                    Some(Payload::Pixels(chunk)) => {
                        let Some((_, buf)) = current.as_mut() else {
                            return Err(Status::invalid_argument("pixels must follow a frame message"));
                        };
                        if chunk.is_empty() || chunk.len() > limits.max_chunk_bytes as usize {
                            return Err(Status::invalid_argument(format!(
                                "chunk size must be 1..={} bytes, got {}",
                                limits.max_chunk_bytes,
                                chunk.len()
                            )));
                        }
                        if buf.len() + chunk.len() > frame_bytes {
                            return Err(Status::invalid_argument(format!(
                                "frame has more than {frame_bytes} bytes ({width}x{height} RGBA)"
                            )));
                        }
                        buf.extend_from_slice(&chunk);
                    }
                    Some(Payload::Options(_)) => {
                        return Err(Status::invalid_argument(
                            "options must be sent once, as the first message",
                        ));
                    }
                    None => return Err(Status::invalid_argument("request message without payload")),
                }
            }
            match current.take() {
                Some(done) => {
                    check_frame_complete(&done.1, frame_bytes)?;
                    let _ = frame_tx.send(done).await;
                    Ok(())
                }
                None => Err(Status::invalid_argument("animation needs at least one frame")),
            }
        }
        .await;
        drop(frame_tx);
        let encoded = worker
            .await
            .map_err(|e| Status::internal(format!("worker task failed: {e}")));
        // 요청 쪽 오류를 우선 알리고, 없으면 인코더 결과를 쓴다.
        received?;
        let encoded = encoded??;

        // 헤더 → WebP 조각. 조각을 다 넘길 때까지 슬롯을 유지하고, 메모리 예약은 결과 버퍼에 붙인다(ADR-0016).
        let (tx, rx) = mpsc::channel(RESPONSE_CHANNEL_DEPTH);
        tokio::spawn(async move {
            let mut slot = slot;
            let header = pb::EncodedHeader {
                webp_bytes: encoded.len() as u64,
            };
            let header = pb::EncodeAnimationResponse {
                payload: Some(pb::encode_animation_response::Payload::Header(header)),
            };
            if send_until(&tx, Ok(header), deadline).await == Sent::Ok {
                send_chunks(
                    &tx,
                    reserved_bytes(encoded, slot.take_memory()),
                    limits.max_chunk_bytes as usize,
                    deadline,
                    |b| pb::EncodeAnimationResponse {
                        payload: Some(pb::encode_animation_response::Payload::WebpData(b)),
                    },
                )
                .await;
            }
        });
        Ok(Response::new(Box::pin(DeadlineStream::new(
            ReceiverStream::new(rx),
            deadline,
        ))))
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn effective_max_pixels_client_stricter_uses_client_value() {
        // given
        let limits = Limits::default();

        // when
        let max = effective_max_pixels(&limits, Some(100));

        // then
        assert_eq!(max, 100);
    }

    #[test]
    fn effective_max_pixels_client_looser_keeps_server_value() {
        // given
        let limits = Limits::default();

        // when
        let max = effective_max_pixels(&limits, Some(u64::MAX));

        // then
        assert_eq!(max, limits.max_pixels);
    }

    #[test]
    fn check_input_bytes_over_limit_is_resource_exhausted() {
        // given
        let limits = Limits {
            max_input_bytes: 10,
            ..Limits::default()
        };

        // when
        let status = check_input_bytes(11, &limits).unwrap_err();

        // then
        assert_eq!(status.code(), tonic::Code::ResourceExhausted);
    }

    #[test]
    fn check_pixel_format_unknown_value_is_invalid_argument() {
        // when
        let status = check_pixel_format(99).unwrap_err();

        // then
        assert_eq!(status.code(), tonic::Code::InvalidArgument);
    }

    #[test]
    fn to_status_limit_exceeded_maps_to_resource_exhausted() {
        // when
        let status = to_status(WebpError::LimitExceeded("x".into()));

        // then
        assert_eq!(status.code(), tonic::Code::ResourceExhausted);
    }
}
