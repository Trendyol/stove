use crate::http::server::AppState;
use axum::http::HeaderMap;
use axum::http::header::HOST;
use axum::{Json, extract::State};
use serde::Serialize;
use utoipa::ToSchema;

use crate::STOVE_SERVER_VERSION;

#[derive(Serialize, ToSchema)]
pub struct MetaResponse {
  pub stove_server_version: &'static str,
  pub mcp: McpMeta,
}

#[derive(Serialize, ToSchema)]
pub struct McpMeta {
  pub enabled: bool,
  pub transport: &'static str,
  pub endpoint: String,
  pub scope: &'static str,
}

#[utoipa::path(
  get,
  path = "/api/v1/meta",
  tag = "system",
  responses((status = 200, description = "Server version and capabilities", body = MetaResponse))
)]
pub async fn get_meta(State(state): State<AppState>, headers: HeaderMap) -> Json<MetaResponse> {
  let endpoint = headers
    .get(HOST)
    .and_then(|value| value.to_str().ok())
    .filter(|host| !host.trim().is_empty())
    .map_or_else(|| "/mcp".to_string(), |host| format!("http://{host}/mcp"));
  let endpoint = state
    .public_url
    .map_or(endpoint, |base| format!("{base}/mcp"));
  Json(MetaResponse {
    stove_server_version: STOVE_SERVER_VERSION,
    mcp: McpMeta {
      enabled: true,
      transport: "streamable-http",
      endpoint,
      scope: "read-only-test-observability",
    },
  })
}
