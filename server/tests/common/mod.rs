//! 통합 테스트 공용 도구: 임시 디렉터리의 UDS에 서버를 띄우고 클라이언트로 붙는다.
#![allow(dead_code)]

use bytes::Bytes;
use hyper_util::rt::TokioIo;
use std::path::PathBuf;
use tokio::net::{UnixListener, UnixStream};
use tokio::sync::oneshot;
use tokio_stream::StreamExt;
use tokio_stream::wrappers::UnixListenerStream;
use tonic::Status;
use tonic::transport::{Channel, Endpoint, Uri};
use webp_sidecar::admission::RequestPolicy;
use webp_sidecar::config::Limits;
use webp_sidecar::pool::{BufferPool, PoolConfig};
use webp_sidecar::proto as pb;
use webp_sidecar::proto::web_p_service_client::WebPServiceClient;
use webp_sidecar::router;
use webp_sidecar::service::WebpService;

/// 테스트가 보내는 조각 크기. 단위: 바이트.
pub const CHUNK: usize = 1024 * 1024;

/// 실행 중인 테스트 서버. Drop하면 종료하고 임시 디렉터리를 지운다.
pub struct TestServer {
    _dir: tempfile::TempDir,
    pub socket: PathBuf,
    stop: Option<oneshot::Sender<()>>,
}

impl Drop for TestServer {
    fn drop(&mut self) {
        if let Some(stop) = self.stop.take() {
            let _ = stop.send(());
        }
    }
}

impl TestServer {
    /// 기본 상한, 동시 처리 2로 띄운다.
    pub async fn start() -> Self {
        Self::start_with(Limits::default(), 2).await
    }

    /// 주어진 상한과 동시 처리 수로 띄운다.
    pub async fn start_with(limits: Limits, max_concurrency: usize) -> Self {
        Self::start_with_policy(limits, max_concurrency, RequestPolicy::default()).await
    }

    /// 대기·시간 상한(ADR-0017)을 지정해 띄운다.
    pub async fn start_with_policy(limits: Limits, max_concurrency: usize, policy: RequestPolicy) -> Self {
        Self::start_with_pool(limits, max_concurrency, PoolConfig::default(), policy).await
    }

    /// 요청 몫의 메모리 예산(ADR-0016)을 `budget_bytes`로 정해 띄운다. 출력 버퍼 풀은 끈다(예산 = `max_memory_bytes`).
    pub async fn start_with_budget(max_concurrency: usize, budget_bytes: u64) -> Self {
        let policy = RequestPolicy {
            max_memory_bytes: budget_bytes,
            ..RequestPolicy::default()
        };
        let pool = PoolConfig {
            max_bytes: 0,
            ..PoolConfig::default()
        };
        Self::start_with_pool(Limits::default(), max_concurrency, pool, policy).await
    }

    /// 상한, 동시 처리 수, 출력 버퍼 풀, 요청 허용 정책을 모두 지정해 띄운다.
    pub async fn start_with_pool(
        limits: Limits,
        max_concurrency: usize,
        pool: PoolConfig,
        policy: RequestPolicy,
    ) -> Self {
        let dir = tempfile::tempdir().expect("tempdir");
        let socket = dir.path().join("webp.sock");
        let listener = UnixListener::bind(&socket).expect("bind uds");
        let (stop, stopped) = oneshot::channel::<()>();
        let service = WebpService::with_config(limits, max_concurrency, BufferPool::new(pool), policy);
        let router = router(service).await;
        tokio::spawn(
            router.serve_with_incoming_shutdown(UnixListenerStream::new(listener), async {
                let _ = stopped.await;
            }),
        );
        Self {
            _dir: dir,
            socket,
            stop: Some(stop),
        }
    }

    /// UDS로 붙은 채널.
    pub async fn channel(&self) -> Channel {
        channel_at(self.socket.clone()).await
    }

    /// WebPService 클라이언트.
    pub async fn client(&self) -> WebPServiceClient<Channel> {
        WebPServiceClient::new(self.channel().await)
    }
}

/// 주어진 UDS 경로의 서버(예: 컨테이너)에 붙은 채널.
pub async fn channel_at(socket: PathBuf) -> Channel {
    {
        Endpoint::try_from("http://[::]:50051")
            .expect("endpoint")
            .connect_with_connector(tower::service_fn(move |_: Uri| {
                let socket = socket.clone();
                async move { Ok::<_, std::io::Error>(TokioIo::new(UnixStream::connect(socket).await?)) }
            }))
            .await
            .expect("connect")
    }
}

fn chunks(data: &[u8]) -> Vec<Bytes> {
    data.chunks(CHUNK).map(Bytes::copy_from_slice).collect()
}

/// Decode를 호출해 헤더와 픽셀을 모은다.
pub async fn decode(
    client: &mut WebPServiceClient<Channel>,
    data: &[u8],
    options: pb::DecodeOptions,
) -> Result<(pb::FrameHeader, Vec<u8>), Status> {
    use pb::decode_request::Payload;
    let mut messages = vec![pb::DecodeRequest {
        payload: Some(Payload::Options(options)),
    }];
    messages.extend(chunks(data).into_iter().map(|c| pb::DecodeRequest {
        payload: Some(Payload::WebpData(c)),
    }));
    decode_raw(client, messages).await
}

/// 임의의 요청 메시지 목록으로 Decode를 호출한다(프로토콜 위반 테스트용).
pub async fn decode_raw(
    client: &mut WebPServiceClient<Channel>,
    messages: Vec<pb::DecodeRequest>,
) -> Result<(pb::FrameHeader, Vec<u8>), Status> {
    use pb::decode_response::Payload;
    let mut stream = client.decode(tokio_stream::iter(messages)).await?.into_inner();
    let mut header = None;
    let mut pixels = Vec::new();
    while let Some(message) = stream.next().await {
        match message?.payload {
            Some(Payload::Header(h)) => {
                assert!(header.is_none(), "header must come once, first");
                header = Some(h);
            }
            Some(Payload::Pixels(p)) => {
                assert!(header.is_some(), "pixels before header");
                assert!(!p.is_empty() && p.len() <= CHUNK, "chunk size {}", p.len());
                pixels.extend_from_slice(&p);
            }
            None => panic!("response without payload"),
        }
    }
    let header = header.expect("missing header");
    assert_eq!(
        header.pixel_bytes as usize,
        pixels.len(),
        "pixel_bytes must match the received total"
    );
    Ok((header, pixels))
}

/// 기본 옵션(RGBA8, 프레임 0)의 DecodeOptions.
pub fn decode_options(data: &[u8]) -> pb::DecodeOptions {
    pb::DecodeOptions {
        input_bytes: data.len() as u64,
        frame_index: 0,
        pixel_format: pb::PixelFormat::Rgba8 as i32,
        max_pixels: None,
    }
}

/// Encode를 호출해 결과 WebP 파일을 모은다.
pub async fn encode(
    client: &mut WebPServiceClient<Channel>,
    pixels: &[u8],
    width: u32,
    height: u32,
    params: pb::EncodeParams,
) -> Result<Vec<u8>, Status> {
    use pb::encode_request::Payload;
    let options = pb::EncodeOptions {
        width,
        height,
        pixel_format: pb::PixelFormat::Rgba8 as i32,
        params: Some(params),
        metadata: None,
    };
    let mut messages = vec![pb::EncodeRequest {
        payload: Some(Payload::Options(options)),
    }];
    messages.extend(chunks(pixels).into_iter().map(|c| pb::EncodeRequest {
        payload: Some(Payload::Pixels(c)),
    }));
    let mut stream = client.encode(tokio_stream::iter(messages)).await?.into_inner();
    let mut declared = None;
    let mut out = Vec::new();
    while let Some(message) = stream.next().await {
        match message?.payload {
            Some(pb::encode_response::Payload::Header(h)) => declared = Some(h.webp_bytes),
            Some(pb::encode_response::Payload::WebpData(d)) => out.extend_from_slice(&d),
            None => panic!("response without payload"),
        }
    }
    assert_eq!(
        declared,
        Some(out.len() as u64),
        "webp_bytes must match the received total"
    );
    Ok(out)
}

/// GetFeatures를 호출한다.
pub async fn get_features(
    client: &mut WebPServiceClient<Channel>,
    data: &[u8],
    include_metadata: bool,
) -> Result<pb::GetFeaturesResponse, Status> {
    use pb::get_features_request::Payload;
    let options = pb::GetFeaturesOptions {
        input_bytes: data.len() as u64,
        include_metadata,
    };
    let mut messages = vec![pb::GetFeaturesRequest {
        payload: Some(Payload::Options(options)),
    }];
    messages.extend(chunks(data).into_iter().map(|c| pb::GetFeaturesRequest {
        payload: Some(Payload::WebpData(c)),
    }));
    Ok(client.get_features(tokio_stream::iter(messages)).await?.into_inner())
}

/// 결정적인 RGBA 테스트 패턴(그라디언트 + 단순 잡음). alpha는 `with_alpha`가 false면 255.
pub fn pattern(width: u32, height: u32, with_alpha: bool) -> Vec<u8> {
    let mut out = Vec::with_capacity(width as usize * height as usize * 4);
    let mut state: u32 = 0x1234_5678;
    for y in 0..height {
        for x in 0..width {
            // xorshift로 작은 잡음을 섞어 lossy 인코더가 일을 하게 한다.
            state ^= state << 13;
            state ^= state >> 17;
            state ^= state << 5;
            let noise = (state & 0x0f) as u8;
            // saturating: 255를 넘어 0 근처로 돌아가는 인위적 불연속을 만들지 않는다(PSNR을 왜곡한다).
            out.push(((x * 255 / width.max(1)) as u8).saturating_add(noise));
            out.push(((y * 255 / height.max(1)) as u8).saturating_add(noise));
            out.push((((x + y) * 127 / (width + height).max(1)) as u8).saturating_add(noise));
            out.push(if with_alpha { ((x ^ y) & 0xff) as u8 } else { 255 });
        }
    }
    out
}

/// 두 RGBA 버퍼의 PSNR(RGB 채널만). 단위: dB. 완전히 같으면 무한대.
pub fn psnr_rgb(a: &[u8], b: &[u8]) -> f64 {
    assert_eq!(a.len(), b.len());
    let mut sum = 0f64;
    let mut n = 0f64;
    for (pa, pb) in a.chunks(4).zip(b.chunks(4)) {
        for c in 0..3 {
            let d = f64::from(pa[c]) - f64::from(pb[c]);
            sum += d * d;
            n += 1.0;
        }
    }
    if sum == 0.0 {
        f64::INFINITY
    } else {
        10.0 * (255.0f64 * 255.0 / (sum / n)).log10()
    }
}

/// DecodeAnimation 결과: 특성과 프레임(헤더, 픽셀) 목록.
pub type Animation = (pb::ImageFeatures, Vec<(pb::FrameHeader, Vec<u8>)>);

/// DecodeAnimation을 호출해 모든 프레임을 모은다.
pub async fn decode_animation(client: &mut WebPServiceClient<Channel>, data: &[u8]) -> Result<Animation, Status> {
    use pb::decode_animation_request::Payload as Req;
    use pb::decode_animation_response::Payload as Resp;
    let options = pb::DecodeAnimationOptions {
        input_bytes: data.len() as u64,
        pixel_format: pb::PixelFormat::Rgba8 as i32,
        max_pixels: None,
    };
    let mut messages = vec![pb::DecodeAnimationRequest {
        payload: Some(Req::Options(options)),
    }];
    messages.extend(chunks(data).into_iter().map(|c| pb::DecodeAnimationRequest {
        payload: Some(Req::WebpData(c)),
    }));
    let mut stream = client
        .decode_animation(tokio_stream::iter(messages))
        .await?
        .into_inner();
    let mut features = None;
    let mut frames: Vec<(pb::FrameHeader, Vec<u8>)> = Vec::new();
    while let Some(message) = stream.next().await {
        match message?.payload {
            Some(Resp::Features(f)) => {
                assert!(
                    features.is_none() && frames.is_empty(),
                    "features must come once, first"
                );
                features = Some(f);
            }
            Some(Resp::Frame(h)) => {
                assert!(features.is_some(), "frame before features");
                frames.push((h, Vec::new()));
            }
            Some(Resp::Pixels(p)) => frames.last_mut().expect("pixels before frame").1.extend_from_slice(&p),
            None => panic!("response without payload"),
        }
    }
    for (h, px) in &frames {
        assert_eq!(h.pixel_bytes as usize, px.len(), "frame {} pixel_bytes", h.frame_index);
    }
    Ok((features.expect("missing features"), frames))
}

/// EncodeAnimation을 호출한다. 각 프레임은 (표시 시간 ms, 캔버스 RGBA8).
pub async fn encode_animation(
    client: &mut WebPServiceClient<Channel>,
    options: pb::EncodeAnimationOptions,
    frames: &[(u32, Vec<u8>)],
) -> Result<Vec<u8>, Status> {
    use pb::encode_animation_request::Payload as Req;
    let mut messages = vec![pb::EncodeAnimationRequest {
        payload: Some(Req::Options(options)),
    }];
    for (duration_ms, rgba) in frames {
        messages.push(pb::EncodeAnimationRequest {
            payload: Some(Req::Frame(pb::EncodeFrame {
                duration_ms: *duration_ms,
            })),
        });
        messages.extend(chunks(rgba).into_iter().map(|c| pb::EncodeAnimationRequest {
            payload: Some(Req::Pixels(c)),
        }));
    }
    encode_animation_raw(client, messages).await
}

/// 임의의 요청 메시지 목록으로 EncodeAnimation을 호출한다(프로토콜 위반 테스트용).
pub async fn encode_animation_raw(
    client: &mut WebPServiceClient<Channel>,
    messages: Vec<pb::EncodeAnimationRequest>,
) -> Result<Vec<u8>, Status> {
    use pb::encode_animation_response::Payload as Resp;
    let mut stream = client
        .encode_animation(tokio_stream::iter(messages))
        .await?
        .into_inner();
    let mut declared = None;
    let mut out = Vec::new();
    while let Some(message) = stream.next().await {
        match message?.payload {
            Some(Resp::Header(h)) => declared = Some(h.webp_bytes),
            Some(Resp::WebpData(d)) => out.extend_from_slice(&d),
            None => panic!("response without payload"),
        }
    }
    assert_eq!(declared, Some(out.len() as u64));
    Ok(out)
}

/// 메타데이터를 넣어 Encode를 호출한다.
pub async fn encode_with_metadata(
    client: &mut WebPServiceClient<Channel>,
    pixels: &[u8],
    width: u32,
    height: u32,
    metadata: pb::Metadata,
) -> Result<Vec<u8>, Status> {
    use pb::encode_request::Payload;
    let options = pb::EncodeOptions {
        width,
        height,
        pixel_format: pb::PixelFormat::Rgba8 as i32,
        params: Some(pb::EncodeParams {
            lossless: true,
            quality: None,
            method: None,
        }),
        metadata: Some(metadata),
    };
    let mut messages = vec![pb::EncodeRequest {
        payload: Some(Payload::Options(options)),
    }];
    messages.extend(chunks(pixels).into_iter().map(|c| pb::EncodeRequest {
        payload: Some(Payload::Pixels(c)),
    }));
    let mut stream = client.encode(tokio_stream::iter(messages)).await?.into_inner();
    let mut out = Vec::new();
    while let Some(message) = stream.next().await {
        if let Some(pb::encode_response::Payload::WebpData(d)) = message?.payload {
            out.extend_from_slice(&d);
        }
    }
    Ok(out)
}
