use std::net::{IpAddr, SocketAddr};

use axum::extract::ConnectInfo;
use axum::http::header::{ACCEPT, HOST, ORIGIN};
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

pub(crate) fn validate_local_request(
  connect_info: &ConnectInfo<SocketAddr>,
  headers: &HeaderMap,
) -> Option<Response> {
  let ConnectInfo(addr) = connect_info;
  if !addr.ip().is_loopback() {
    return Some(forbidden("MCP is only available to loopback clients"));
  }

  let host = headers
    .get(HOST)
    .and_then(|value| value.to_str().ok())
    .and_then(host_without_port);
  if !host.as_deref().is_some_and(is_loopback_host) {
    return Some(forbidden("MCP requests must use a localhost Host header"));
  }

  if let Some(origin) = headers.get(ORIGIN).and_then(|value| value.to_str().ok())
    && !origin_host(origin).is_some_and(|host| is_loopback_host(&host))
  {
    return Some(forbidden("MCP requests must use a localhost Origin"));
  }

  None
}

fn forbidden(message: &str) -> Response {
  super::protocol::rpc_error(
    None,
    StatusCode::FORBIDDEN,
    -32000,
    "Forbidden",
    Some(json!({ "reason": message })),
  )
}

fn host_without_port(host: &str) -> Option<String> {
  let host = host.trim();
  if host.is_empty() {
    return None;
  }
  if let Some(rest) = host.strip_prefix('[') {
    return rest.find(']').map(|end| rest[..end].to_string());
  }
  Some(host.split(':').next().unwrap_or(host).to_string())
}

fn origin_host(origin: &str) -> Option<String> {
  let after_scheme = origin.split_once("://").map_or(origin, |(_, rest)| rest);
  let authority = after_scheme.split('/').next().unwrap_or(after_scheme);
  host_without_port(authority)
}

fn is_loopback_host(host: &str) -> bool {
  host.eq_ignore_ascii_case("localhost")
    || host
      .parse::<IpAddr>()
      .is_ok_and(|ip_address| ip_address.is_loopback())
}

#[cfg(test)]
mod tests {
  use super::*;

  #[test]
  fn host_parser_handles_ipv6_loopback() {
    assert_eq!(host_without_port("[::1]:4040").as_deref(), Some("::1"));
    assert!(is_loopback_host("::1"));
  }

  #[test]
  fn origin_parser_rejects_remote_hosts() {
    assert_eq!(
      origin_host("http://localhost:4040").as_deref(),
      Some("localhost")
    );
    assert!(
      !origin_host("https://example.com")
        .as_deref()
        .is_some_and(is_loopback_host)
    );
  }
}
