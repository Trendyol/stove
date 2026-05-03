mod analysis;
mod args;
mod contract;
mod protocol;
mod security;
mod tools;

use std::net::SocketAddr;

use analysis::Analyzer;
use axum::body::Bytes;
use axum::extract::{ConnectInfo, State};
use axum::http::{HeaderMap, StatusCode};
use axum::response::{IntoResponse, Response};
use serde_json::{Value, json};

use crate::http::server::AppState;

use self::contract::MethodName;
use self::protocol::{JsonRpcRequest, RpcError, ToolCallParams};

pub async fn handle_get(
  State(_state): State<AppState>,
  connect_info: ConnectInfo<SocketAddr>,
  headers: HeaderMap,
) -> Response {
  if let Some(response) = security::validate_local_request(&connect_info, &headers) {
    return response;
  }

  (
    StatusCode::METHOD_NOT_ALLOWED,
    axum::Json(json!({
      "error": "Stove MCP is a stateless Streamable HTTP endpoint in v1. Use HTTP POST with JSON-RPC requests.",
    })),
  )
    .into_response()
}

pub async fn handle_post(
  State(state): State<AppState>,
  connect_info: ConnectInfo<SocketAddr>,
  headers: HeaderMap,
  body: Bytes,
) -> Response {
  if let Some(response) = security::validate_local_request(&connect_info, &headers) {
    return response;
  }

  if let Some(response) = security::validate_accept_header(&headers) {
    return response;
  }

  let request = match serde_json::from_slice::<JsonRpcRequest>(&body) {
    Ok(request) => request,
    Err(error) => {
      return protocol::rpc_error(
        None,
        StatusCode::BAD_REQUEST,
        -32700,
        "Parse error",
        Some(json!({ "error": error.to_string() })),
      );
    }
  };

  let id = request.id.clone();
  if request.id.is_none() {
    return StatusCode::ACCEPTED.into_response();
  }

  match handle_request(state, request).await {
    Ok(result) => protocol::rpc_result(id, result),
    Err(error) => protocol::rpc_error(id, StatusCode::OK, error.code, &error.message, error.data),
  }
}

async fn handle_request(state: AppState, request: JsonRpcRequest) -> Result<Value, RpcError> {
  match MethodName::from_str(&request.method) {
    Some(MethodName::Initialize) => Ok(protocol::initialize_result()),
    Some(MethodName::Ping) => Ok(json!({})),
    Some(MethodName::ToolsList) => Ok(json!({ "tools": tools::definitions() })),
    Some(MethodName::ToolsCall) => {
      let params: ToolCallParams =
        serde_json::from_value(request.params.unwrap_or_else(|| json!({}))).map_err(|error| {
          RpcError::invalid_params(format!("invalid tools/call params: {error}"))
        })?;
      let analyzer = Analyzer::new(state.repository, state.ingestor);
      let arguments = params.arguments.unwrap_or_else(|| json!({}));
      let output = analyzer
        .call_tool(&params.name, arguments)
        .await
        .map_err(RpcError::tool_error)?;
      Ok(protocol::tool_result(output))
    }
    None => Err(RpcError::method_not_found(format!(
      "unsupported MCP method: {}",
      request.method
    ))),
  }
}
