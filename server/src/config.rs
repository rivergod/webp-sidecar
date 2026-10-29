//! 서버 설정. 명령줄 인자 → 환경변수 → 기본값 순서로 해석한다(ADR-0012).
//!
//! 키 표: docs/design/grpc-api.md §3.1. 환경변수 조회를 함수로 주입받아 테스트가 프로세스 환경에 의존하지 않게 한다.

use crate::admission::{MAX_BUDGET_BYTES, RequestPolicy};
use crate::pool::PoolConfig;
use clap::Parser;
use std::fmt;
use std::net::SocketAddr;
use std::path::PathBuf;

/// gRPC 기본 최대 메시지 크기. 단위: 바이트. (tonic/grpc 기본값 4 MiB)
pub const GRPC_DEFAULT_MAX_MESSAGE_BYTES: u64 = 4 * 1024 * 1024;

/// 한 메시지에 데이터와 함께 실리는 다른 필드(oneof 태그, 프레임 목록 등)를 위한 여유. 단위: 바이트.
pub const MESSAGE_HEADROOM_BYTES: u64 = 64 * 1024;

/// 명령줄 인자. 지정하지 않은 값은 환경변수, 그다음 기본값을 쓴다.
#[derive(Debug, Default, Parser)]
#[command(
    name = "webp-imageio-server",
    version,
    about = "libwebp gRPC sidecar server (webp.v1)"
)]
pub struct Cli {
    /// UDS 리슨 경로 [env: WEBP_SERVER_UDS_PATH] [기본: /run/webp-imageio/webp.sock]
    #[arg(long)]
    pub uds_path: Option<PathBuf>,
    /// 소켓 파일 모드(8진수) [env: WEBP_SERVER_UDS_MODE] [기본: 0660]
    #[arg(long)]
    pub uds_mode: Option<String>,
    /// TCP 리슨 주소. 지정하지 않으면 TCP를 켜지 않는다 [env: WEBP_SERVER_TCP_ADDR]
    #[arg(long)]
    pub tcp_addr: Option<String>,
    /// 압축 입력 최대 크기(바이트) [env: WEBP_SERVER_MAX_INPUT_BYTES] [기본: 67108864]
    #[arg(long)]
    pub max_input_bytes: Option<String>,
    /// 캔버스 최대 픽셀 수 [env: WEBP_SERVER_MAX_PIXELS] [기본: 67108864]
    #[arg(long)]
    pub max_pixels: Option<String>,
    /// 애니메이션 최대 프레임 수 [env: WEBP_SERVER_MAX_FRAMES] [기본: 1000]
    #[arg(long)]
    pub max_frames: Option<String>,
    /// ICC+EXIF+XMP 합계 최대 크기(바이트) [env: WEBP_SERVER_MAX_METADATA_BYTES] [기본: 2097152]
    #[arg(long)]
    pub max_metadata_bytes: Option<String>,
    /// 데이터 조각 최대 크기(바이트) [env: WEBP_SERVER_MAX_CHUNK_BYTES] [기본: 1048576]
    #[arg(long)]
    pub max_chunk_bytes: Option<String>,
    /// 동시에 libwebp 작업을 하는 요청 수 [env: WEBP_SERVER_MAX_CONCURRENCY] [기본: 2]
    #[arg(long)]
    pub max_concurrency: Option<String>,
    /// 요청 하나의 합성 픽셀 수 상한(캔버스 × 프레임) [env: WEBP_SERVER_MAX_COMPOSITED_PIXELS] [기본: 1073741824]
    #[arg(long)]
    pub max_composited_pixels: Option<String>,
    /// 슬롯을 잡은 뒤 요청 하나의 시간 상한(ms) [env: WEBP_SERVER_MAX_REQUEST_MS] [기본: 60000]
    #[arg(long)]
    pub max_request_ms: Option<String>,
    /// 슬롯을 기다릴 수 있는 요청 수. 0이면 상한 없음 [env: WEBP_SERVER_MAX_QUEUED_REQUESTS] [기본: 8]
    #[arg(long)]
    pub max_queued_requests: Option<String>,
    /// 정지 이미지 출력 버퍼 풀 총량 상한(바이트). 0이면 끈다 [env: WEBP_SERVER_BUFFER_POOL_BYTES] [기본: 134217728]
    #[arg(long)]
    pub buffer_pool_bytes: Option<String>,
    /// 풀 버퍼 유휴 만료(ms) [env: WEBP_SERVER_BUFFER_POOL_IDLE_MS] [기본: 30000]
    #[arg(long)]
    pub buffer_pool_idle_ms: Option<String>,
    /// 요청들과 출력 버퍼 풀이 함께 쓸 메모리 합계(바이트). 요청 몫은 풀 총량 상한을 뺀 값
    /// [env: WEBP_SERVER_MAX_MEMORY_BYTES] [기본: 805306368]
    #[arg(long)]
    pub max_memory_bytes: Option<String>,
}

/// 서버가 적용하는 상한. `GetInfo`의 `Limits`로 그대로 노출한다(docs/design/grpc-api.md §3).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Limits {
    /// 압축 입력 최대 크기. 단위: 바이트.
    pub max_input_bytes: u64,
    /// 캔버스 최대 픽셀 수(width × height).
    pub max_pixels: u64,
    /// 애니메이션 최대 프레임 수.
    pub max_frames: u32,
    /// 메타데이터 합계 최대 크기. 단위: 바이트.
    pub max_metadata_bytes: u64,
    /// 데이터 조각 최대 크기. 단위: 바이트.
    pub max_chunk_bytes: u32,
    /// 요청 하나가 합성하는 캔버스 픽셀 수의 합 상한(ADR-0017).
    pub max_composited_pixels: u64,
}

impl Default for Limits {
    /// ADR-0012의 기본값.
    fn default() -> Self {
        Self {
            max_input_bytes: 64 * 1024 * 1024,
            max_pixels: 8192 * 8192,
            max_frames: 1000,
            max_metadata_bytes: 2 * 1024 * 1024,
            max_chunk_bytes: 1024 * 1024,
            max_composited_pixels: 1 << 30,
        }
    }
}

/// 해석과 검증이 끝난 서버 설정.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Config {
    /// UDS 리슨 경로.
    pub uds_path: PathBuf,
    /// 소켓 파일 모드(예: 0o660).
    pub uds_mode: u32,
    /// TCP 리슨 주소. `None`이면 TCP를 켜지 않는다.
    pub tcp_addr: Option<SocketAddr>,
    /// 요청 상한.
    pub limits: Limits,
    /// 동시에 libwebp 작업을 하는 요청 수(1 이상).
    pub max_concurrency: usize,
    /// 정지 이미지 출력 버퍼 풀(ADR-0018).
    pub buffer_pool: PoolConfig,
    /// 대기 상한과 요청 시간 상한(ADR-0017).
    pub request_policy: RequestPolicy,
}

/// 설정 값을 해석하거나 검증하다 실패했다.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ConfigError(pub String);

impl fmt::Display for ConfigError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(f, "invalid configuration: {}", self.0)
    }
}

impl std::error::Error for ConfigError {}

/// 키 하나를 명령줄 → 환경변수 순서로 찾는다. 둘 다 없으면 `None`.
fn lookup(cli: Option<&str>, env_key: &str, env: &impl Fn(&str) -> Option<String>) -> Option<(String, String)> {
    match cli {
        Some(v) => Some((
            v.to_string(),
            format!(
                "--{}",
                env_key
                    .trim_start_matches("WEBP_SERVER_")
                    .to_lowercase()
                    .replace('_', "-")
            ),
        )),
        None => env(env_key).map(|v| (v, env_key.to_string())),
    }
}

fn parse_num<T: std::str::FromStr>(
    cli: Option<&str>,
    env_key: &str,
    default: T,
    env: &impl Fn(&str) -> Option<String>,
) -> Result<T, ConfigError> {
    match lookup(cli, env_key, env) {
        None => Ok(default),
        Some((raw, source)) => raw
            .trim()
            .parse()
            .map_err(|_| ConfigError(format!("{source}: not a valid number: {raw:?}"))),
    }
}

impl Config {
    /// 명령줄 인자와 환경변수 조회 함수로 설정을 해석하고 검증한다.
    ///
    /// 오류: 숫자·주소·모드 형식이 틀리거나, 값이 범위를 벗어나면 [`ConfigError`].
    /// 특히 `max_chunk_bytes`, `max_metadata_bytes`는 gRPC 기본 메시지 한도(4 MiB)에서 여유분을 뺀 값 이하여야 한다
    /// (ADR-0003: 어떤 메시지도 기본 한도를 넘지 않는다).
    pub fn resolve(cli: &Cli, env: impl Fn(&str) -> Option<String>) -> Result<Self, ConfigError> {
        let d = Limits::default();
        let uds_path = match lookup(
            cli.uds_path.as_deref().and_then(|p| p.to_str()),
            "WEBP_SERVER_UDS_PATH",
            &env,
        ) {
            Some((v, _)) if !v.trim().is_empty() => PathBuf::from(v),
            Some((_, source)) => return Err(ConfigError(format!("{source}: must not be empty"))),
            None => PathBuf::from("/run/webp-imageio/webp.sock"),
        };
        let uds_mode = match lookup(cli.uds_mode.as_deref(), "WEBP_SERVER_UDS_MODE", &env) {
            None => 0o660,
            Some((raw, source)) => u32::from_str_radix(raw.trim(), 8)
                .ok()
                .filter(|m| *m <= 0o777)
                .ok_or_else(|| ConfigError(format!("{source}: not an octal file mode: {raw:?}")))?,
        };
        let tcp_addr = match lookup(cli.tcp_addr.as_deref(), "WEBP_SERVER_TCP_ADDR", &env) {
            None => None,
            Some((raw, _)) if raw.trim().is_empty() => None,
            Some((raw, source)) => Some(
                raw.trim()
                    .parse()
                    .map_err(|_| ConfigError(format!("{source}: not a socket address: {raw:?}")))?,
            ),
        };
        let limits = Limits {
            max_input_bytes: parse_num(
                cli.max_input_bytes.as_deref(),
                "WEBP_SERVER_MAX_INPUT_BYTES",
                d.max_input_bytes,
                &env,
            )?,
            max_pixels: parse_num(cli.max_pixels.as_deref(), "WEBP_SERVER_MAX_PIXELS", d.max_pixels, &env)?,
            max_frames: parse_num(cli.max_frames.as_deref(), "WEBP_SERVER_MAX_FRAMES", d.max_frames, &env)?,
            max_metadata_bytes: parse_num(
                cli.max_metadata_bytes.as_deref(),
                "WEBP_SERVER_MAX_METADATA_BYTES",
                d.max_metadata_bytes,
                &env,
            )?,
            max_chunk_bytes: parse_num(
                cli.max_chunk_bytes.as_deref(),
                "WEBP_SERVER_MAX_CHUNK_BYTES",
                d.max_chunk_bytes,
                &env,
            )?,
            max_composited_pixels: parse_num(
                cli.max_composited_pixels.as_deref(),
                "WEBP_SERVER_MAX_COMPOSITED_PIXELS",
                d.max_composited_pixels,
                &env,
            )?,
        };
        let max_concurrency = parse_num(
            cli.max_concurrency.as_deref(),
            "WEBP_SERVER_MAX_CONCURRENCY",
            2usize,
            &env,
        )?;

        // 범위 검사. 메시지 하나에 실리는 값은 기본 메시지 한도 아래여야 한다(ADR-0003).
        let message_budget = GRPC_DEFAULT_MAX_MESSAGE_BYTES - MESSAGE_HEADROOM_BYTES;
        if limits.max_chunk_bytes == 0 || u64::from(limits.max_chunk_bytes) > message_budget {
            return Err(ConfigError(format!("max_chunk_bytes must be 1..={message_budget}")));
        }
        if limits.max_metadata_bytes > message_budget {
            return Err(ConfigError(format!("max_metadata_bytes must be <= {message_budget}")));
        }
        if limits.max_input_bytes == 0
            || limits.max_pixels == 0
            || limits.max_frames == 0
            || limits.max_composited_pixels == 0
        {
            return Err(ConfigError(
                "max_input_bytes, max_pixels, max_frames and max_composited_pixels must be >= 1".into(),
            ));
        }
        if max_concurrency == 0 {
            return Err(ConfigError("max_concurrency must be >= 1".into()));
        }
        let pool_default = PoolConfig::default();
        let buffer_pool_bytes = parse_num(
            cli.buffer_pool_bytes.as_deref(),
            "WEBP_SERVER_BUFFER_POOL_BYTES",
            pool_default.max_bytes,
            &env,
        )?;
        let buffer_pool_idle_ms = parse_num(
            cli.buffer_pool_idle_ms.as_deref(),
            "WEBP_SERVER_BUFFER_POOL_IDLE_MS",
            pool_default.idle.as_millis() as u64,
            &env,
        )?;
        if buffer_pool_idle_ms == 0 {
            return Err(ConfigError("buffer_pool_idle_ms must be >= 1".into()));
        }
        let policy_default = RequestPolicy::default();
        let max_request_ms = parse_num(
            cli.max_request_ms.as_deref(),
            "WEBP_SERVER_MAX_REQUEST_MS",
            policy_default.max_request.as_millis() as u64,
            &env,
        )?;
        if max_request_ms == 0 {
            return Err(ConfigError("max_request_ms must be >= 1".into()));
        }
        let max_queued_requests = parse_num(
            cli.max_queued_requests.as_deref(),
            "WEBP_SERVER_MAX_QUEUED_REQUESTS",
            policy_default.max_queued_requests,
            &env,
        )?;
        // ADR-0016: 요청 몫(max_memory_bytes - 풀 총량 상한)이 1 KiB 이상이고 예산 단위(KiB 허가)의 한도 안이어야 한다.
        let max_memory_bytes = parse_num(
            cli.max_memory_bytes.as_deref(),
            "WEBP_SERVER_MAX_MEMORY_BYTES",
            policy_default.max_memory_bytes,
            &env,
        )?;
        let request_budget = max_memory_bytes.saturating_sub(buffer_pool_bytes);
        if !(1024..=MAX_BUDGET_BYTES).contains(&request_budget) {
            return Err(ConfigError(format!(
                "max_memory_bytes ({max_memory_bytes}) minus buffer_pool_bytes ({buffer_pool_bytes}) must be \
                 1024..={MAX_BUDGET_BYTES}"
            )));
        }
        Ok(Self {
            uds_path,
            uds_mode,
            tcp_addr,
            limits,
            max_concurrency,
            buffer_pool: PoolConfig {
                max_bytes: buffer_pool_bytes,
                idle: std::time::Duration::from_millis(buffer_pool_idle_ms),
            },
            request_policy: RequestPolicy {
                max_queued_requests,
                max_request: std::time::Duration::from_millis(max_request_ms),
                max_memory_bytes,
            },
        })
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::collections::HashMap;

    fn env_of(pairs: &[(&str, &str)]) -> impl Fn(&str) -> Option<String> {
        let map: HashMap<String, String> = pairs.iter().map(|(k, v)| (k.to_string(), v.to_string())).collect();
        move |k| map.get(k).cloned()
    }

    #[test]
    fn resolve_nothing_set_uses_adr_0012_defaults() {
        // when
        let c = Config::resolve(&Cli::default(), env_of(&[])).unwrap();

        // then
        assert_eq!(c.uds_path, PathBuf::from("/run/webp-imageio/webp.sock"));
        assert_eq!(c.uds_mode, 0o660);
        assert_eq!(c.tcp_addr, None);
        assert_eq!(c.limits, Limits::default());
        assert_eq!(c.max_concurrency, 2);
        assert_eq!(c.limits.max_composited_pixels, 1 << 30);
        assert_eq!(c.request_policy, RequestPolicy::default());
    }

    #[test]
    fn resolve_adr_0017_keys_from_env_are_applied() {
        // when
        let c = Config::resolve(
            &Cli::default(),
            env_of(&[
                ("WEBP_SERVER_MAX_COMPOSITED_PIXELS", "1000"),
                ("WEBP_SERVER_MAX_REQUEST_MS", "250"),
                ("WEBP_SERVER_MAX_QUEUED_REQUESTS", "0"),
            ]),
        )
        .unwrap();

        // then
        assert_eq!(c.limits.max_composited_pixels, 1000);
        assert_eq!(c.request_policy.max_request, std::time::Duration::from_millis(250));
        assert_eq!(c.request_policy.max_queued_requests, 0);
    }

    #[test]
    fn resolve_max_memory_bytes_default_is_768_mib() {
        // when
        let c = Config::resolve(&Cli::default(), env_of(&[])).unwrap();

        // then
        assert_eq!(c.request_policy.max_memory_bytes, 768 * 1024 * 1024);
    }

    #[test]
    fn resolve_max_memory_bytes_from_env_is_applied() {
        // when
        let c = Config::resolve(
            &Cli::default(),
            env_of(&[
                ("WEBP_SERVER_MAX_MEMORY_BYTES", "2147483648"),
                ("WEBP_SERVER_BUFFER_POOL_BYTES", "0"),
            ]),
        )
        .unwrap();

        // then
        assert_eq!(c.request_policy.max_memory_bytes, 2 << 30);
    }

    #[test]
    fn resolve_max_memory_not_above_buffer_pool_is_rejected() {
        // when: 기본 풀 128 MiB와 같은 예산이면 요청 몫이 0이다
        let err = Config::resolve(
            &Cli::default(),
            env_of(&[("WEBP_SERVER_MAX_MEMORY_BYTES", "134217728")]),
        )
        .unwrap_err();

        // then
        assert!(err.0.contains("max_memory_bytes"), "{err}");
    }

    #[test]
    fn resolve_zero_request_time_or_composited_limit_is_rejected() {
        // when
        let time = Config::resolve(&Cli::default(), env_of(&[("WEBP_SERVER_MAX_REQUEST_MS", "0")]));
        let composited = Config::resolve(&Cli::default(), env_of(&[("WEBP_SERVER_MAX_COMPOSITED_PIXELS", "0")]));

        // then
        assert!(time.is_err());
        assert!(composited.is_err());
    }

    #[test]
    fn resolve_env_set_overrides_default() {
        // when
        let c = Config::resolve(&Cli::default(), env_of(&[("WEBP_SERVER_MAX_PIXELS", "100")])).unwrap();

        // then
        assert_eq!(c.limits.max_pixels, 100);
    }

    #[test]
    fn resolve_cli_and_env_set_cli_wins() {
        // given
        let cli = Cli {
            max_pixels: Some("200".into()),
            ..Default::default()
        };

        // when
        let c = Config::resolve(&cli, env_of(&[("WEBP_SERVER_MAX_PIXELS", "100")])).unwrap();

        // then
        assert_eq!(c.limits.max_pixels, 200);
    }

    #[test]
    fn resolve_tcp_addr_set_parses_socket_address() {
        // when
        let c = Config::resolve(&Cli::default(), env_of(&[("WEBP_SERVER_TCP_ADDR", "127.0.0.1:50051")])).unwrap();

        // then
        assert_eq!(c.tcp_addr, Some("127.0.0.1:50051".parse().unwrap()));
    }

    #[test]
    fn resolve_chunk_above_message_budget_is_rejected() {
        // when
        let err = Config::resolve(&Cli::default(), env_of(&[("WEBP_SERVER_MAX_CHUNK_BYTES", "4194304")])).unwrap_err();

        // then
        assert!(err.0.contains("max_chunk_bytes"), "{err}");
    }

    #[test]
    fn resolve_non_numeric_value_names_the_source() {
        // when
        let err = Config::resolve(&Cli::default(), env_of(&[("WEBP_SERVER_MAX_FRAMES", "lots")])).unwrap_err();

        // then
        assert!(err.0.contains("WEBP_SERVER_MAX_FRAMES"), "{err}");
    }

    #[test]
    fn resolve_non_octal_mode_is_rejected() {
        // when
        let err = Config::resolve(&Cli::default(), env_of(&[("WEBP_SERVER_UDS_MODE", "0999")])).unwrap_err();

        // then
        assert!(err.0.contains("octal"), "{err}");
    }

    #[test]
    fn resolve_zero_concurrency_is_rejected() {
        // when
        let err = Config::resolve(&Cli::default(), env_of(&[("WEBP_SERVER_MAX_CONCURRENCY", "0")])).unwrap_err();

        // then
        assert!(err.0.contains("max_concurrency"), "{err}");
    }
}
