use axum::http::header::ACCEPT;
use axum::http::{HeaderMap, StatusCode};
use axum::response::Response;
use serde_json::json;

pub(crate) fn validate_accept_header(headers: &HeaderMap) -> Option<Response> {
  let accept = headers.get(ACCEPT).and_then(|value| value.to_str().ok())?;

  if accept.contains("application/json")
    || accept.contains("*/*")
    || accept.contains("text/event-stream")
  {
    None
  } else {
    Some(super::protocol::rpc_error(
      None,
      StatusCode::NOT_ACCEPTABLE,
      -32000,
      "Not acceptable",
      Some(json!({ "expected_accept": "application/json or text/event-stream" })),
    ))
  }
}
