use axum::Json;
use serde::Serialize;

use crate::STOVE_CLI_VERSION;

#[derive(Serialize)]
pub struct MetaResponse {
  pub stove_cli_version: &'static str,
}

pub async fn get_meta() -> Json<MetaResponse> {
  Json(MetaResponse {
    stove_cli_version: STOVE_CLI_VERSION,
  })
}
