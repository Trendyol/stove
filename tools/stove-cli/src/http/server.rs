use std::sync::Arc;

use axum::Router;
use axum::routing::{delete, get};
use tower_http::cors::CorsLayer;

use crate::ingest::EventIngestor;
use crate::sse::manager::SseManager;
use crate::storage::repository::Repository;

/// Shared application state passed to all HTTP handlers.
#[derive(Clone)]
pub struct AppState {
  pub repository: Arc<Repository>,
  pub sse_manager: Arc<SseManager>,
  pub ingestor: Option<EventIngestor>,
}

/// Create the axum router with all API routes, SSE, and embedded SPA.
pub fn create_router(repository: Arc<Repository>, sse_manager: Arc<SseManager>) -> Router {
  create_router_with_ingestor(repository, sse_manager, None)
}

/// Create the axum router with an optional ingest flush handle for MCP reads.
pub fn create_router_with_ingestor(
  repository: Arc<Repository>,
  sse_manager: Arc<SseManager>,
  ingestor: Option<EventIngestor>,
) -> Router {
  let state = AppState {
    repository,
    sse_manager,
    ingestor,
  };

  let api = Router::new()
    .route("/meta", get(super::routes::get_meta))
    .route("/apps", get(super::routes::get_apps))
    .route("/runs", get(super::routes::get_runs))
    .route("/runs/{run_id}", get(super::routes::get_run))
    .route("/runs/{run_id}/tests", get(super::routes::get_tests))
    .route(
      "/runs/{run_id}/tests/{test_id}/entries",
      get(super::routes::get_entries),
    )
    .route(
      "/runs/{run_id}/tests/{test_id}/spans",
      get(super::routes::get_test_spans),
    )
    .route(
      "/runs/{run_id}/tests/{test_id}/snapshots",
      get(super::routes::get_snapshots),
    )
    .route("/traces/{trace_id}", get(super::routes::get_trace))
    .route("/events/stream", get(super::routes::sse_handler))
    .route("/data", delete(super::routes::clear_all));

  Router::new()
    .route(
      "/mcp",
      get(crate::mcp::handle_get).post(crate::mcp::handle_post),
    )
    .nest("/api/v1", api)
    .fallback(super::routes::static_handler)
    .layer(CorsLayer::permissive())
    .with_state(state)
}
