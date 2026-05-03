use std::net::SocketAddr;
use std::sync::Arc;

use clap::Parser;
use tracing::info;

use stove::config;
use stove::grpc;
use stove::http;
use stove::ingest;
use stove::proto;
use stove::skills;
use stove::sse;
use stove::storage;

#[tokio::main]
async fn main() -> anyhow::Result<()> {
  tracing_subscriber::fmt()
    .with_env_filter(
      tracing_subscriber::EnvFilter::try_from_default_env().unwrap_or_else(|_| "info".into()),
    )
    .init();

  let config = config::Config::parse();

  // Handle a `skills` subcommand if requested. Returns true when handled.
  if skills::handle_skills_command(&config).await? {
    return Ok(());
  }

  // Handle --fresh-start: back up and delete the existing database
  if config.fresh_start {
    if let Some(backup_path) = config::handle_fresh_start(&config.db)? {
      info!("Backed up database to {}", backup_path);
      println!("  Backed up database to {backup_path}");
    }
    println!("  Starting fresh — database will be recreated.");
  }

  // Initialize database
  let db = storage::database::Database::open(&config.db)?;
  let repository = Arc::new(storage::repository::Repository::new(db));

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
  let ingestor = ingest::EventIngestor::new(repository.clone());

  // Start gRPC server
  let grpc_addr: SocketAddr = format!("0.0.0.0:{}", config.grpc_port).parse()?;
  let grpc_service = grpc::service::DashboardEventServiceImpl::new_with_ingestor(
    repository.clone(),
    sse_manager.clone(),
    ingestor.clone(),
  );
  let grpc_handle = tokio::spawn(async move {
    info!("gRPC server listening on {}", grpc_addr);
    tonic::transport::Server::builder()
      .add_service(
        proto::dashboard_event_service_server::DashboardEventServiceServer::new(grpc_service),
      )
      .serve(grpc_addr)
      .await
  });

  // Start HTTP server
  let http_addr: SocketAddr = format!("0.0.0.0:{}", config.port).parse()?;
  let router = http::server::create_router_with_ingestor(repository, sse_manager, Some(ingestor));
  let http_handle = tokio::spawn(async move {
    info!("HTTP server listening on {}", http_addr);
    let listener = tokio::net::TcpListener::bind(http_addr).await?;
    axum::serve(
      listener,
      router.into_make_service_with_connect_info::<SocketAddr>(),
    )
    .await
  });

  println!(
    "\n  Stove CLI v{} running\n  UI:   http://localhost:{}\n  REST: http://localhost:{}/api/v1\n  MCP:  http://localhost:{}/mcp\n  gRPC: localhost:{}\n",
    env!("STOVE_VERSION"),
    config.port,
    config.port,
    config.port,
    config.grpc_port
  );

  // Wait for either server to finish (or error)
  tokio::select! {
      result = grpc_handle => {
          result??;
      }
      result = http_handle => {
          result??;
      }
  }

  Ok(())
}
