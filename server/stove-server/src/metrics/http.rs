use super::{ActiveGuard, AppMetrics};
use axum::{
  extract::{MatchedPath, Request, State},
  middleware::Next,
  response::Response,
};
use prometheus::HistogramTimer;
use std::sync::Arc;

pub(crate) async fn observe_http(
  State(metrics): State<Arc<AppMetrics>>,
  request: Request,
  next: Next,
) -> Response {
  let route = request
    .extensions()
    .get::<MatchedPath>()
    .map_or("unmatched", MatchedPath::as_str)
    .to_owned();
  // Scrapes must not create traffic in their own HTTP graphs. Handle configured prefixes too.
  if route == "/metrics" || route.ends_with("/metrics") {
    return next.run(request).await;
  }
  let method = match request.method().as_str() {
    "GET" | "POST" | "PUT" | "PATCH" | "DELETE" | "HEAD" | "OPTIONS" | "CONNECT" | "TRACE" => {
      request.method().as_str()
    }
    _ => "OTHER",
  }
  .to_owned();
  let active = ActiveGuard::new(metrics.http_in_flight.with_label_values(&[&route, &method]));
  let mut guard = RequestGuard {
    _timer: metrics
      .http_duration
      .with_label_values(&[&route, &method])
      .start_timer(),
    metrics,
    route,
    method,
    status: "cancelled".into(),
    _active: active,
  };
  let response = next.run(request).await;
  guard.status = response.status().as_u16().to_string();
  response
}
struct RequestGuard {
  metrics: Arc<AppMetrics>,
  route: String,
  method: String,
  _timer: HistogramTimer,
  status: String,
  _active: ActiveGuard,
}
impl Drop for RequestGuard {
  fn drop(&mut self) {
    self
      .metrics
      .http_requests
      .with_label_values(&[&self.route, &self.method, &self.status])
      .inc();
  }
}
