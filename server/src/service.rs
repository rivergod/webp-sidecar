//! webp.v1 `WebPService` 구현.
//!
//! - 스트리밍 규칙: docs/design/grpc-api.md §2 (요청: options → 데이터 조각 → half-close, 응답: 헤더 → 조각들)
//! - 상한과 검사 시점: §3, 오류 매핑: §4
//! - libwebp 작업은 blocking 스레드 풀에서 하고, 동시에 작업하는 요청 수를 세마포어로 제한한다(ADR-0012).

use crate::config::Limits;
use crate::proto::{self as pb, web_p_service_server::WebPService};
use crate::webp::{self, WebpError};
use bytes::Bytes;
use std::pin::Pin;
use std::sync::Arc;
use tokio::sync::{OwnedSemaphorePermit, Semaphore, mpsc};
use tokio_stream::Stream;
use tokio_stream::wrappers::ReceiverStream;
use tonic::{Request, Response, Status, Streaming};

/// 응답 채널 깊이. 작게 두어 클라이언트가 느리면 서버 전송이 멈추게 한다(gRPC 흐름 제어와 함께 동작).
const RESPONSE_CHANNEL_DEPTH: usize = 4;

type ResponseStream<T> = Pin<Box<dyn Stream<Item = Result<T, Status>> + Send + 'static>>;

/// `WebPService` 구현체. 복제해도 상한과 동시 처리 슬롯을 공유한다(UDS·TCP 리스너가 같은 슬롯을 쓴다).
#[derive(Debug, Clone)]
pub struct WebpService {
    limits: Limits,
    permits: Arc<Semaphore>,
}

impl WebpService {
    /// 상한과 동시 처리 수(1 이상)로 서비스를 만든다.
    pub fn new(limits: Limits, max_concurrency: usize) -> Self {
        Self {
            limits,
            permits: Arc::new(Semaphore::new(max_concurrency.max(1))),
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
    pb::EncodeRequest,
    pb::encode_request::Payload,
    pb::EncodeOptions,
    Pixels
);

/// 요청 스트림을 끝까지 받아 options와 데이터를 모은다.
///
/// `expected_len`은 options를 검증하고 뒤따를 데이터의 바이트 수를 돌려준다. 검증이 끝나면 **입력 버퍼를 할당하기
/// 전에** 동시 처리 슬롯을 잡는다. 그래서 큰 입력(인코딩 픽셀은 최대 수백 MiB)을 받는 요청 수도 슬롯 수로
/// 제한된다(ADR-0012 메모리 예산). 슬롯은 돌려받은 쪽이 응답 전송이 끝날 때까지 들고 있는다.
/// 슬롯을 기다리는 동안 클라이언트가 취소하면 tonic이 이 future를 버리므로 대기도 함께 끝난다.
/// 조각 크기·순서·합계 검사는 docs/design/grpc-api.md §2.2를 따른다.
async fn collect_input<M: InputMessage>(
    stream: &mut Streaming<M>,
    max_chunk_bytes: u32,
    permits: &Arc<Semaphore>,
    expected_len: impl FnOnce(&M::Options) -> Result<u64, Status>,
) -> Result<(M::Options, Vec<u8>, OwnedSemaphorePermit), Status> {
    // 첫 메시지는 options여야 한다.
    let first = stream
        .message()
        .await?
        .ok_or_else(|| Status::invalid_argument("empty request stream: the first message must be options"))?;
    let Some(Part::Options(options)) = first.into_part() else {
        return Err(Status::invalid_argument("the first message must be options"));
    };

    // 선언된 크기를 검증하고, 슬롯을 얻은 뒤 그만큼만 할당한다.
    let expected = expected_len(&options)?;
    let permit = permits
        .clone()
        .acquire_owned()
        .await
        .map_err(|_| Status::unavailable("server is shutting down"))?;
    let expected = usize::try_from(expected)
        .map_err(|_| Status::resource_exhausted(format!("{expected} bytes do not fit in memory")))?;
    let mut buf = Vec::new();
    buf.try_reserve_exact(expected)
        .map_err(|_| Status::resource_exhausted(format!("cannot allocate {expected} bytes for the input")))?;

    // 조각을 모은다. 크기와 순서가 어긋나면 클라이언트 버그이므로 INVALID_ARGUMENT.
    while let Some(message) = stream.message().await? {
        match message.into_part() {
            Some(Part::Data(chunk)) => {
                if chunk.is_empty() || chunk.len() > max_chunk_bytes as usize {
                    return Err(Status::invalid_argument(format!(
                        "chunk size must be 1..={max_chunk_bytes} bytes, got {}",
                        chunk.len()
                    )));
                }
                if buf.len() + chunk.len() > expected {
                    return Err(Status::invalid_argument(format!(
                        "received more than the declared {expected} bytes"
                    )));
                }
                buf.extend_from_slice(&chunk);
            }
            Some(Part::Options(_)) => {
                return Err(Status::invalid_argument(
                    "options must be sent once, as the first message",
                ));
            }
            None => return Err(Status::invalid_argument("request message without payload")),
        }
    }
    if buf.len() != expected {
        return Err(Status::invalid_argument(format!(
            "declared {expected} bytes but received {}",
            buf.len()
        )));
    }
    Ok((options, buf, permit))
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

/// `data`를 `chunk` 바이트 이하 조각으로 나눠 보낸다. 복사 없이 `Bytes`를 자른다. 수신 측이 떠나면 false.
async fn send_chunks<T>(
    tx: &mpsc::Sender<Result<T, Status>>,
    data: Bytes,
    chunk: usize,
    wrap: impl Fn(Bytes) -> T,
) -> bool {
    let mut start = 0;
    while start < data.len() {
        let end = (start + chunk).min(data.len());
        if tx.send(Ok(wrap(data.slice(start..end)))).await.is_err() {
            return false;
        }
        start = end;
    }
    true
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

fn has_any_metadata(m: Option<&pb::Metadata>) -> bool {
    m.is_some_and(|m| m.icc_profile.is_some() || m.exif.is_some() || m.xmp.is_some())
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
        let (options, data, permit) = collect_input(
            &mut stream,
            limits.max_chunk_bytes,
            &self.permits,
            |o: &pb::GetFeaturesOptions| check_input_bytes(o.input_bytes, &limits),
        )
        .await?;

        // 헤더와 청크 목록만 읽는다(픽셀 디코딩 없음). 입력 버퍼를 들고 있는 동안 슬롯을 유지한다.
        let response = run_blocking(move || {
            let _permit = permit;
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
        let (options, data, permit) = collect_input(
            &mut stream,
            limits.max_chunk_bytes,
            &self.permits,
            |o: &pb::DecodeOptions| check_input_bytes(o.input_bytes, &limits),
        )
        .await?;
        check_pixel_format(options.pixel_format)?;

        // 헤더만 읽어(빠름) 상한을 먼저 검사한다. 넘으면 버퍼를 할당하지 않고 거부한다.
        let features = webp::features(&data).map_err(to_status)?;
        check_canvas(&features, effective_max_pixels(&limits, options.max_pixels))?;
        if features.has_animation {
            return Err(Status::unimplemented("decoding animated WebP is not implemented yet"));
        }
        if options.frame_index != 0 {
            return Err(Status::out_of_range(format!(
                "frame_index {} is out of range for a still image (1 frame)",
                options.frame_index
            )));
        }

        // 디코딩하고 헤더 → 픽셀 조각 순서로 보낸다. 픽셀 버퍼가 남아 있는 동안(전송이 끝날 때까지) 슬롯을 유지한다.
        let (tx, rx) = mpsc::channel(RESPONSE_CHANNEL_DEPTH);
        tokio::spawn(async move {
            let _permit = permit;
            match run_blocking(move || webp::decode_still_rgba(&data, &features).map_err(to_status)).await {
                Ok(pixels) => {
                    let header = frame_header(0, features.width, features.height, pixels.len(), features.has_alpha, 0);
                    let header = pb::DecodeResponse {
                        payload: Some(pb::decode_response::Payload::Header(header)),
                    };
                    if tx.send(Ok(header)).await.is_ok() {
                        send_chunks(&tx, Bytes::from(pixels), limits.max_chunk_bytes as usize, |b| {
                            pb::DecodeResponse {
                                payload: Some(pb::decode_response::Payload::Pixels(b)),
                            }
                        })
                        .await;
                    }
                }
                Err(status) => {
                    let _ = tx.send(Err(status)).await;
                }
            }
        });
        Ok(Response::new(Box::pin(ReceiverStream::new(rx))))
    }

    async fn decode_animation(
        &self,
        _request: Request<Streaming<pb::DecodeAnimationRequest>>,
    ) -> Result<Response<Self::DecodeAnimationStream>, Status> {
        Err(Status::unimplemented("DecodeAnimation is not implemented yet"))
    }

    async fn encode(
        &self,
        request: Request<Streaming<pb::EncodeRequest>>,
    ) -> Result<Response<Self::EncodeStream>, Status> {
        let limits = self.limits;
        let mut stream = request.into_inner();

        // options를 먼저 검증하고(크기, 상한, 품질) 그 크기만큼 픽셀을 받는다.
        let (options, pixels, permit) = collect_input(
            &mut stream,
            limits.max_chunk_bytes,
            &self.permits,
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
                if has_any_metadata(o.metadata.as_ref()) {
                    return Err(Status::unimplemented("embedding metadata is not implemented yet"));
                }
                webp::encoder_config(&to_webp_params(o.params.as_ref())).map_err(to_status)?;
                Ok(pixel_count * webp::BYTES_PER_PIXEL as u64)
            },
        )
        .await?;

        // 인코딩하고 헤더 → WebP 조각 순서로 보낸다. 전송이 끝날 때까지 슬롯을 유지한다.
        let (tx, rx) = mpsc::channel(RESPONSE_CHANNEL_DEPTH);
        tokio::spawn(async move {
            let _permit = permit;
            let params = to_webp_params(options.params.as_ref());
            let result = run_blocking(move || {
                webp::encode_still_rgba(&pixels, options.width, options.height, &params).map_err(to_status)
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
                    if tx.send(Ok(header)).await.is_ok() {
                        send_chunks(&tx, Bytes::from(encoded), limits.max_chunk_bytes as usize, |b| {
                            pb::EncodeResponse {
                                payload: Some(pb::encode_response::Payload::WebpData(b)),
                            }
                        })
                        .await;
                    }
                }
                Err(status) => {
                    let _ = tx.send(Err(status)).await;
                }
            }
        });
        Ok(Response::new(Box::pin(ReceiverStream::new(rx))))
    }

    async fn encode_animation(
        &self,
        _request: Request<Streaming<pb::EncodeAnimationRequest>>,
    ) -> Result<Response<Self::EncodeAnimationStream>, Status> {
        Err(Status::unimplemented("EncodeAnimation is not implemented yet"))
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
