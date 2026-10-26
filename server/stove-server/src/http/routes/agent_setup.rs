use axum::{
  Json,
  extract::{Query, State},
  http::StatusCode,
};
use serde::Deserialize;
use utoipa::IntoParams;

use crate::agent_setup::catalog::{AgentSetupResponse, catalog};
use crate::http::server::AppState;

#[derive(Deserialize, IntoParams)]
pub struct AgentSetupQuery {
  /// Browser-visible MCP URL, used only when `public_url` is not configured.
  pub endpoint: Option<String>,
}

#[utoipa::path(
  get,
  path = "/api/v1/agent-setup",
  tag = "system",
  params(AgentSetupQuery),
  responses(
    (status = 200, description = "Read-only agent installation instructions", body = AgentSetupResponse),
    (status = 400, description = "Missing or invalid MCP URL")
  )
)]
pub async fn get_agent_setup(
  State(state): State<AppState>,
  Query(query): Query<AgentSetupQuery>,
) -> Result<Json<AgentSetupResponse>, (StatusCode, String)> {
  let endpoint = state
    .public_url
    .map(|base| format!("{base}/mcp"))
    .or(query.endpoint)
    .ok_or_else(|| {
      (
        StatusCode::BAD_REQUEST,
        "Supply the browser-visible MCP endpoint".into(),
      )
    })?;
  let endpoint = crate::navigation::validate_public_url(&endpoint)
    .map_err(|message| (StatusCode::BAD_REQUEST, message))?;
  catalog(endpoint).map(Json).map_err(|error| {
    tracing::error!(%error, "failed to generate agent setup instructions");
    (
      StatusCode::INTERNAL_SERVER_ERROR,
      "Could not generate agent setup instructions".into(),
    )
  })
}
