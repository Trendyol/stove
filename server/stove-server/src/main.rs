use std::net::SocketAddr;
use std::process::ExitCode;
use std::sync::Arc;

use tokio::sync::watch;
use tokio::task::JoinHandle;
use tracing::info;

use stove::config;
use stove::grpc;
use stove::http;
use stove::ingest::EventIngestor;
use stove::proto;
use stove::skills;
use stove::sse;
use stove::storage;

#[tokio::main]
async fn main() -> ExitCode {
  let config = match config::Config::parse() {
    Ok(config) => config,
    Err(error) => {
      eprintln!("Error: {error:#}");
      return ExitCode::FAILURE;
    }
  };
  initialize_logging(config.log_format);
  match run(config).await {
    Ok(()) => ExitCode::SUCCESS,
    Err(error) => {
      tracing::error!(error = %format!("{error:#}"), "Stove server failed");
      ExitCode::FAILURE
    }
  }
}

async fn run(config: config::Config) -> anyhow::Result<()> {
  // Handle a `skills` subcommand if requested. Returns true when handled.
  if skills::handle_skills_command(&config).await? {
    return Ok(());
  }

  prepare_fresh_start(&config)?;
  let repository = open_repository(&config)?;
  repository.initialize_idle_timeout(config.run_idle_timeout_seconds)?;

  // Handle --clear flag
  if config.clear {
    repository.clear_all()?;
    info!("Cleared all stored runs.");
    return Ok(());
  }

  // Suggest or apply Stove agent skills update before serving.
  // Network/IO errors are swallowed inside; never blocks startup.
  skills::maybe_update_skills(&config).await;

  let sse_manager = Arc::new(sse::manager::SseManager::new());
  let live_event_relay = sse::relay::spawn(repository.clone(), sse_manager.clone());
  let ingestor = EventIngestor::new(repository.clone(), sse_manager.clone());
  let (shutdown_tx, shutdown_rx) = watch::channel(false);
  let expiry = stove::run_expiry::spawn(repository.clone(), shutdown_rx.clone());

  let grpc_handle = tokio::spawn(serve_grpc(
    config.grpc_port,
    ingestor.clone(),
    shutdown_rx.clone(),
  ));
  let http_handle = tokio::spawn(serve_http(
    config.port,
    config.public_url.clone(),
    repository,
    sse_manager,
    ingestor,
    shutdown_rx,
  ));
  print_endpoints(config.port, config.grpc_port, config.log_format);
  let result = run_until_shutdown(grpc_handle, http_handle, live_event_relay, shutdown_tx).await;
  expiry.await?;
  result
}

async fn run_until_shutdown(
  mut grpc_handle: JoinHandle<anyhow::Result<()>>,
  mut http_handle: JoinHandle<anyhow::Result<()>>,
  live_event_relay: JoinHandle<()>,
  shutdown_tx: watch::Sender<bool>,
) -> anyhow::Result<()> {
  let server_result = tokio::select! {
    result = &mut grpc_handle => result?,
    result = &mut http_handle => result?,
    () = termination_signal() => Ok(()),
  };
  let _ = shutdown_tx.send(true);
  let _ = tokio::time::timeout(std::time::Duration::from_secs(10), async {
    if !grpc_handle.is_finished() {
      let _ = (&mut grpc_handle).await;
    }
    if !http_handle.is_finished() {
      let _ = (&mut http_handle).await;
    }
  })
  .await;
  live_event_relay.abort();
  server_result
}

fn initialize_logging(format: config::LogFormat) {
  let subscriber = tracing_subscriber::fmt().with_env_filter(
    tracing_subscriber::EnvFilter::try_from_default_env().unwrap_or_else(|_| "info".into()),
  );
  match format {
    config::LogFormat::Json => subscriber.json().with_ansi(false).init(),
    config::LogFormat::Text => subscriber.init(),
  }
}

fn prepare_fresh_start(config: &config::Config) -> anyhow::Result<()> {
  if !config.fresh_start {
    return Ok(());
  }
  if config.database_url.is_some() {
    anyhow::bail!("--fresh-start is only supported for the local SQLite database");
  }
  if let Some(backup_path) = config::handle_fresh_start(&config.db)? {
    info!(backup_path, "Backed up database");
  }
  info!("Starting fresh — database will be recreated.");
  Ok(())
}

fn open_repository(
  config: &config::Config,
) -> anyhow::Result<Arc<storage::repository::Repository>> {
  let repository = if let Some(database_url) = &config.database_url {
    storage::repository::Repository::connect_postgres(database_url, config.retention_runs_per_app)?
  } else {
    storage::repository::Repository::connect_sqlite(&config.db, config.retention_runs_per_app)?
  };
  Ok(Arc::new(repository))
}

async fn serve_grpc(
  port: u16,
  ingestor: EventIngestor,
  shutdown: watch::Receiver<bool>,
) -> anyhow::Result<()> {
  let address = SocketAddr::from(([0, 0, 0, 0], port));
  let service = grpc::service::DashboardEventServiceImpl::new(ingestor);
  info!(%address, "gRPC server listening");
  tonic::transport::Server::builder()
    .add_service(proto::dashboard_event_service_server::DashboardEventServiceServer::new(service))
    .serve_with_shutdown(address, wait_for_shutdown(shutdown))
    .await?;
  Ok(())
}

async fn serve_http(
  port: u16,
  public_url: Option<String>,
  repository: Arc<storage::repository::Repository>,
  sse_manager: Arc<sse::manager::SseManager>,
  ingestor: EventIngestor,
  shutdown: watch::Receiver<bool>,
) -> anyhow::Result<()> {
  let address = SocketAddr::from(([0, 0, 0, 0], port));
  let router =
    http::server::create_router_with_public_url(repository, sse_manager, ingestor, public_url);
  info!(%address, "HTTP server listening");
  let listener = tokio::net::TcpListener::bind(address).await?;
  axum::serve(
    listener,
    router.into_make_service_with_connect_info::<SocketAddr>(),
  )
  .with_graceful_shutdown(wait_for_shutdown(shutdown))
  .await?;
  Ok(())
}

fn print_endpoints(http_port: u16, grpc_port: u16, format: config::LogFormat) {
  if format == config::LogFormat::Json {
    info!(
      version = env!("STOVE_VERSION"),
      ui = %format!("http://localhost:{http_port}"),
      rest = %format!("http://localhost:{http_port}/api/v1"),
      docs = %format!("http://localhost:{http_port}/swagger-ui"),
      mcp = %format!("http://localhost:{http_port}/mcp"),
      grpc = %format!("localhost:{grpc_port}"),
      "Stove Server running"
    );
    return;
  }
  println!(
    "\n  Stove Server v{} running\n  UI:   http://localhost:{}\n  REST: http://localhost:{}/api/v1\n  Docs: http://localhost:{}/swagger-ui\n  MCP:  http://localhost:{}/mcp\n  gRPC: localhost:{}\n",
    env!("STOVE_VERSION"),
    http_port,
    http_port,
    http_port,
    http_port,
    grpc_port
  );
}

async fn wait_for_shutdown(mut shutdown: watch::Receiver<bool>) {
  while !*shutdown.borrow() {
    if shutdown.changed().await.is_err() {
      break;
    }
  }
}

#[cfg(unix)]
async fn termination_signal() {
  let mut terminate = tokio::signal::unix::signal(tokio::signal::unix::SignalKind::terminate())
    .expect("install SIGTERM handler");
  tokio::select! {
    _ = tokio::signal::ctrl_c() => {}
    _ = terminate.recv() => {}
  }
}

#[cfg(not(unix))]
async fn termination_signal() {
  let _ = tokio::signal::ctrl_c().await;
}
