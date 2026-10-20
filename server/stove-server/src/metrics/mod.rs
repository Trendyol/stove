//! Application instruments share the storage registry and its process identity.
//! Labels are route templates and fixed operation names, never request data.
#![allow(clippy::cast_precision_loss)]

mod http;
mod ingestion;
#[cfg(test)]
mod tests;
pub(crate) use http::observe_http;

use prometheus::{
  Gauge, GaugeVec, Histogram, HistogramOpts, HistogramVec, IntCounter, IntCounterVec, IntGauge,
  IntGaugeVec, Opts, Registry, core::Collector,
};
use std::sync::Arc;

pub(crate) fn register<T: Collector + Clone + 'static>(registry: &Registry, metric: T) -> T {
  registry
    .register(Box::new(metric.clone()))
    .expect("unique metric names");
  metric
}
pub(crate) fn gauge_vec(registry: &Registry, name: &str, help: &str, labels: &[&str]) -> GaugeVec {
  register(
    registry,
    GaugeVec::new(Opts::new(name, help), labels).expect("constant gauge labels"),
  )
}
pub(crate) fn int_gauge_vec(
  registry: &Registry,
  name: &str,
  help: &str,
  labels: &[&str],
) -> IntGaugeVec {
  register(
    registry,
    IntGaugeVec::new(Opts::new(name, help), labels).expect("constant gauge labels"),
  )
}
pub(crate) fn counter_vec(
  registry: &Registry,
  name: &str,
  help: &str,
  labels: &[&str],
) -> IntCounterVec {
  register(
    registry,
    IntCounterVec::new(Opts::new(name, help), labels).expect("constant counter labels"),
  )
}
fn histogram(registry: &Registry, name: &str, help: &str, labels: &[&str]) -> HistogramVec {
  register(
    registry,
    HistogramVec::new(
      HistogramOpts::new(name, help).buckets(vec![
        0.001, 0.005, 0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1., 2.5, 5., 10., 30., 60.,
      ]),
      labels,
    )
    .expect("constant histogram labels"),
  )
}

pub(crate) struct AppMetrics {
  http_requests: IntCounterVec,
  http_duration: HistogramVec,
  http_in_flight: IntGaugeVec,
  ingestion: ingestion::IngestionMetrics,
  pub streams: IntGaugeVec,
  pub stream_errors: IntCounterVec,
  pub sse_lagged: prometheus::IntCounter,
  worker_runs: IntCounterVec,
  worker_success: GaugeVec,
  pub runtime_delay: Histogram,
}

impl AppMetrics {
  pub fn new(registry: &Registry) -> Arc<Self> {
    #[cfg(target_os = "linux")]
    registry
      .register(Box::new(
        prometheus::process_collector::ProcessCollector::new(
          i32::try_from(std::process::id()).expect("process ID fits pid_t"),
          "stove_app",
        ),
      ))
      .expect("unique process collector");
    let metrics = Arc::new(Self {
      http_requests: counter_vec(
        registry,
        "stove_app_http_requests_total",
        "HTTP responses by matched route, method and status; cancelled handlers use status cancelled.",
        &["route", "method", "status"],
      ),
      http_duration: histogram(
        registry,
        "stove_app_http_request_duration_seconds",
        "HTTP handler time until response headers, excluding streaming body lifetime and metrics scrapes.",
        &["route", "method"],
      ),
      http_in_flight: int_gauge_vec(
        registry,
        "stove_app_http_requests_in_flight",
        "HTTP handlers currently awaiting response headers.",
        &["route", "method"],
      ),
      ingestion: ingestion::IngestionMetrics::new(registry),
      streams: int_gauge_vec(
        registry,
        "stove_app_streams_active",
        "Active SSE connections and gRPC ingestion streams.",
        &["transport"],
      ),
      stream_errors: counter_vec(
        registry,
        "stove_app_stream_errors_total",
        "Stream receive or durable replay failures.",
        &["transport"],
      ),
      sse_lagged: register(
        registry,
        prometheus::IntCounter::new(
          "stove_app_sse_lagged_events_total",
          "Broadcast events missed by slow SSE receivers; recovered by durable replay.",
        )
        .expect("constant counter name"),
      ),
      worker_runs: counter_vec(
        registry,
        "stove_app_worker_runs_total",
        "Background worker attempts by outcome; errors include unwinding or cancellation.",
        &["worker", "outcome"],
      ),
      worker_success: gauge_vec(
        registry,
        "stove_app_worker_last_success_timestamp_seconds",
        "Last successful background worker attempt; zero until first success.",
        &["worker"],
      ),
      runtime_delay: histogram(
        registry,
        "stove_app_runtime_scheduling_delay_seconds",
        "Delay beyond the scheduled one-second Tokio heartbeat.",
        &[],
      )
      .with_label_values::<&str>(&[]),
    });
    for transport in ["sse", "grpc"] {
      metrics.streams.with_label_values(&[transport]);
      metrics.stream_errors.with_label_values(&[transport]);
    }
    metrics
  }

  pub fn refresh(&self) {
    self.ingestion.refresh();
  }

  pub fn stream(&self, transport: &'static str) -> ActiveGuard {
    ActiveGuard::new(self.streams.with_label_values(&[transport]))
  }

  pub fn worker(&self, name: &'static str) -> WorkerGuard {
    WorkerGuard {
      successes: self.worker_runs.with_label_values(&[name, "success"]),
      errors: self.worker_runs.with_label_values(&[name, "error"]),
      last_success: self.worker_success.with_label_values(&[name]),
      success: false,
    }
  }
}

pub(crate) struct ActiveGuard(IntGauge);
impl ActiveGuard {
  fn new(gauge: IntGauge) -> Self {
    gauge.inc();
    Self(gauge)
  }
}
impl Drop for ActiveGuard {
  fn drop(&mut self) {
    self.0.dec();
  }
}

pub(crate) struct WorkerGuard {
  successes: IntCounter,
  errors: IntCounter,
  last_success: Gauge,
  success: bool,
}
impl WorkerGuard {
  pub fn finish(mut self, success: bool) {
    self.success = success;
  }
  pub fn heartbeat(&self) {
    self.last_success.set(now());
  }
}
impl Drop for WorkerGuard {
  fn drop(&mut self) {
    if self.success {
      self.successes.inc();
      self.heartbeat();
    } else {
      self.errors.inc();
    }
  }
}
fn now() -> f64 {
  chrono::Utc::now().timestamp_millis() as f64 / 1000.
}
