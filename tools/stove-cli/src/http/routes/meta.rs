use axum::Json;
use axum::http::HeaderMap;
use axum::http::header::HOST;
use serde::Serialize;

use crate::STOVE_CLI_VERSION;

#[derive(Serialize)]
pub struct MetaResponse {
  pub stove_cli_version: &'static str,
  pub mcp: McpMeta,
}

#[derive(Serialize)]
pub struct McpMeta {
  pub enabled: bool,
  pub transport: &'static str,
  pub endpoint: String,
  pub scope: &'static str,
}

pub async fn get_meta(headers: HeaderMap) -> Json<MetaResponse> {
  let endpoint = headers
    .get(HOST)
    .and_then(|value| value.to_str().ok())
    .filter(|host| !host.trim().is_empty())
    .map_or_else(|| "/mcp".to_string(), |host| format!("http://{host}/mcp"));
  Json(MetaResponse {
    stove_cli_version: STOVE_CLI_VERSION,
    mcp: McpMeta {
      enabled: true,
      transport: "streamable-http",
      endpoint,
      scope: "read-only-test-observability",
    },
  })
}
