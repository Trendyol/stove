use axum::http::StatusCode;
use axum::response::{IntoResponse, Response};
use serde::Deserialize;
use serde_json::{Value, json};

use super::analysis::ToolOutput;
use crate::STOVE_CLI_VERSION;

const PROTOCOL_VERSION: &str = "2025-06-18";

pub(crate) fn initialize_result() -> Value {
  json!({
    "protocolVersion": PROTOCOL_VERSION,
    "capabilities": {
      "tools": {
        "listChanged": false
      }
    },
    "serverInfo": {
      "name": "stove",
      "version": STOVE_CLI_VERSION,
      "title": "Stove test observability"
    },
    "instructions": "Use Stove MCP to inspect recorded e2e test failures through compact app/run/test scoped tools. If MCP is unavailable, incomplete, or ambiguous, fall back to normal test output, Stove reports, and logs."
  })
}

pub(crate) fn tool_result(output: ToolOutput) -> Value {
  let ToolOutput { structured, text } = output;
  json!({
    "content": [
      {
        "type": "text",
        "text": text
      }
    ],
    "structuredContent": structured,
    "isError": false
  })
}

pub(crate) fn rpc_result(id: Option<Value>, result: Value) -> Response {
  let mut envelope = serde_json::Map::new();
  envelope.insert("jsonrpc".to_string(), Value::String("2.0".to_string()));
  envelope.insert("id".to_string(), id.unwrap_or(Value::Null));
  envelope.insert("result".to_string(), result);
  (StatusCode::OK, axum::Json(Value::Object(envelope))).into_response()
}

pub(crate) fn rpc_error(
  id: Option<Value>,
  status: StatusCode,
  code: i32,
  message: &str,
  data: Option<Value>,
) -> Response {
  let mut error = json!({
    "code": code,
    "message": message,
  });
  if let Some(data) = data {
    error["data"] = data;
  }

  (
    status,
    axum::Json(json!({
      "jsonrpc": "2.0",
      "id": id.unwrap_or(Value::Null),
      "error": error,
    })),
  )
    .into_response()
}

#[derive(Debug, Deserialize)]
pub(crate) struct JsonRpcRequest {
  pub(crate) id: Option<Value>,
  pub(crate) method: String,
  pub(crate) params: Option<Value>,
}

#[derive(Debug, Deserialize)]
pub(crate) struct ToolCallParams {
  pub(crate) name: String,
  pub(crate) arguments: Option<Value>,
}

pub(crate) struct RpcError {
  pub(crate) code: i32,
  pub(crate) message: String,
  pub(crate) data: Option<Value>,
}

impl RpcError {
  pub(crate) fn invalid_params(message: String) -> Self {
    Self {
      code: -32602,
      message,
      data: None,
    }
  }

  pub(crate) fn method_not_found(message: String) -> Self {
    Self {
      code: -32601,
      message,
      data: None,
    }
  }

  pub(crate) fn tool_error(message: String) -> Self {
    Self {
      code: -32001,
      message,
      data: None,
    }
  }
}
