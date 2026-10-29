//! webp-imageio-server 실행 파일. 설정을 읽고 UDS(기본)와 TCP(옵션)에서 서비스한다(ADR-0003, ADR-0012).

use clap::Parser;
use std::os::unix::fs::{FileTypeExt, PermissionsExt};
use std::path::Path;
use std::process::ExitCode;
use tokio::net::{TcpListener, UnixListener};
use tokio::sync::watch;
use tokio_stream::wrappers::UnixListenerStream;
use webp_imageio_server::config::{Cli, Config};
use webp_imageio_server::pool::BufferPool;
use webp_imageio_server::service::WebpService;
use webp_imageio_server::{router, tcp_incoming};

#[tokio::main]
async fn main() -> ExitCode {
    // 로그는 stderr로, 수준은 RUST_LOG(기본 info).
    tracing_subscriber::fmt()
        .with_env_filter(
            tracing_subscriber::EnvFilter::try_from_default_env()
                .unwrap_or_else(|_| tracing_subscriber::EnvFilter::new("info")),
        )
        .with_writer(std::io::stderr)
        // 컨테이너 로그(터미널 아님)에는 ANSI 색 코드를 넣지 않는다.
        .with_ansi(std::io::IsTerminal::is_terminal(&std::io::stderr()))
        .init();

    let config = match Config::resolve(&Cli::parse(), |key| std::env::var(key).ok()) {
        Ok(config) => config,
        Err(e) => {
            eprintln!("{e}");
            return ExitCode::from(2);
        }
    };
    match run(config).await {
        Ok(()) => ExitCode::SUCCESS,
        Err(e) => {
            tracing::error!(error = %e, "server failed");
            ExitCode::FAILURE
        }
    }
}

async fn run(config: Config) -> Result<(), Box<dyn std::error::Error>> {
    let v = webp_imageio_server::webp::versions();
    tracing::info!(
        libwebp = %v.decoder, uds = %config.uds_path.display(), tcp = ?config.tcp_addr,
        limits = ?config.limits, max_concurrency = config.max_concurrency, buffer_pool = ?config.buffer_pool,
        request_policy = ?config.request_policy, "starting"
    );

    // 두 리스너가 같은 서비스(상한, 동시 처리 슬롯)를 공유한다.
    let pool = BufferPool::new(config.buffer_pool);
    let service = WebpService::with_config(
        config.limits,
        config.max_concurrency,
        pool.clone(),
        config.request_policy,
    );
    // 출력 버퍼 풀의 유휴 버퍼를 주기적으로 해제한다(ADR-0018). 부하가 끝나면 상주 메모리가 돌아온다.
    if config.buffer_pool.max_bytes > 0 {
        let period = (config.buffer_pool.idle / 2).max(std::time::Duration::from_millis(1));
        tokio::spawn(async move {
            let mut ticks = tokio::time::interval(period);
            loop {
                ticks.tick().await;
                let freed = pool.evict_expired();
                if freed > 0 {
                    tracing::debug!(freed_bytes = freed, "released idle pool buffers");
                }
            }
        });
    }
    let (stop_tx, stop_rx) = watch::channel(false);

    let uds = bind_uds(&config.uds_path, config.uds_mode)?;
    let uds_server = router(service.clone())
        .await
        .serve_with_incoming_shutdown(UnixListenerStream::new(uds), wait_for_stop(stop_rx.clone()));
    let tcp_server = async {
        match config.tcp_addr {
            Some(addr) => {
                let listener = TcpListener::bind(addr).await?;
                router(service.clone())
                    .await
                    .serve_with_incoming_shutdown(tcp_incoming(listener), wait_for_stop(stop_rx.clone()))
                    .await?;
            }
            None => wait_for_stop(stop_rx.clone()).await,
        }
        Ok::<(), Box<dyn std::error::Error>>(())
    };
    let signals = async {
        shutdown_signal().await;
        tracing::info!("shutting down");
        let _ = stop_tx.send(true);
    };

    let (uds_result, tcp_result, ()) = tokio::join!(uds_server, tcp_server, signals);
    let _ = std::fs::remove_file(&config.uds_path);
    uds_result?;
    tcp_result?;
    Ok(())
}

/// UDS 리스너를 만든다. 이전 실행이 남긴 소켓 파일은 지우지만, 소켓이 아닌 파일은 건드리지 않는다.
fn bind_uds(path: &Path, mode: u32) -> std::io::Result<UnixListener> {
    if let Some(parent) = path.parent() {
        std::fs::create_dir_all(parent)?;
    }
    match std::fs::symlink_metadata(path) {
        Ok(meta) if meta.file_type().is_socket() => std::fs::remove_file(path)?,
        Ok(_) => {
            return Err(std::io::Error::new(
                std::io::ErrorKind::AlreadyExists,
                format!("{} exists and is not a socket", path.display()),
            ));
        }
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => {}
        Err(e) => return Err(e),
    }
    let listener = UnixListener::bind(path)?;
    std::fs::set_permissions(path, std::fs::Permissions::from_mode(mode))?;
    Ok(listener)
}

async fn wait_for_stop(mut rx: watch::Receiver<bool>) {
    let _ = rx.wait_for(|stop| *stop).await;
}

/// SIGTERM(컨테이너 종료) 또는 SIGINT를 기다린다.
async fn shutdown_signal() {
    use tokio::signal::unix::{SignalKind, signal};
    let mut term = signal(SignalKind::terminate()).expect("install SIGTERM handler");
    tokio::select! {
        _ = term.recv() => {}
        _ = tokio::signal::ctrl_c() => {}
    }
}
