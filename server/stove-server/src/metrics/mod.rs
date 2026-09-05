//! Process-local Prometheus metrics with fixed labels and no evidence payloads.
mod database;
mod exposition;
mod operation;

pub(crate) use database::{DatabaseOperation, database_acquire, database_result};
pub(crate) use operation::Operation;
use std::sync::atomic::{AtomicU64, Ordering};

pub(crate) static INGEST: std::sync::LazyLock<Operation> =
  std::sync::LazyLock::new(Operation::default);
pub(crate) static READ: std::sync::LazyLock<Operation> =
  std::sync::LazyLock::new(Operation::default);
pub(crate) static REPLAY: std::sync::LazyLock<Operation> =
  std::sync::LazyLock::new(Operation::default);
static RELAY_LAG: AtomicU64 = AtomicU64::new(0);
static RELAY_ERRORS: AtomicU64 = AtomicU64::new(0);
static RELAY_LAST_SUCCESS: AtomicU64 = AtomicU64::new(0);
static RESYNCS: AtomicU64 = AtomicU64::new(0);
static COMMITTED: AtomicU64 = AtomicU64::new(0);
static DUPLICATES: AtomicU64 = AtomicU64::new(0);

pub(crate) async fn endpoint(
  axum::extract::State(state): axum::extract::State<crate::http::server::AppState>,
) -> impl axum::response::IntoResponse {
  let mut output = String::new();
  exposition::operations(
    &mut output,
    "stove_operations",
    "stove_operation_duration_seconds",
    [("ingest", &*INGEST), ("read", &*READ), ("replay", &*REPLAY)],
  );
  exposition::operations(
    &mut output,
    "stove_database_operations",
    "stove_database_duration_seconds",
    database::OPERATIONS
      .iter()
      .map(|operation| (operation.name(), &database::DATABASE[*operation as usize])),
  );
  for (name, kind, help, counter) in [
    (
      "events_committed_total",
      "counter",
      "New events committed by this process.",
      &COMMITTED,
    ),
    (
      "events_duplicate_total",
      "counter",
      "Duplicate committed events acknowledged again.",
      &DUPLICATES,
    ),
    (
      "relay_lag_ids",
      "gauge",
      "Distance to last observed durable watermark, including deleted IDs.",
      &RELAY_LAG,
    ),
    (
      "relay_errors_total",
      "counter",
      "Failed durable relay polling attempts.",
      &RELAY_ERRORS,
    ),
    (
      "relay_last_success_timestamp_seconds",
      "gauge",
      "Unix timestamp of last successful relay poll; zero before first success.",
      &RELAY_LAST_SUCCESS,
    ),
    (
      "sse_resyncs_total",
      "counter",
      "Explicit client resynchronization attempts.",
      &RESYNCS,
    ),
  ] {
    exposition::scalar(
      &mut output,
      &format!("stove_{name}"),
      kind,
      help,
      counter.load(Ordering::Relaxed),
    );
  }
  let cache = state.sse_manager.metrics();
  for (name, help, value) in [
    (
      "sse_cache_events",
      "Events retained in shared SSE cache.",
      cache.events as u64,
    ),
    (
      "sse_cache_bytes",
      "Serialized bytes retained in shared SSE cache.",
      cache.bytes as u64,
    ),
    (
      "sse_subscribers",
      "Local broadcast receivers.",
      cache.subscribers as u64,
    ),
    (
      "relay_cursor",
      "Last durable event ID observed by this pod relay.",
      cache.cursor,
    ),
  ] {
    exposition::scalar(&mut output, &format!("stove_{name}"), "gauge", help, value);
  }
  (
    [(
      axum::http::header::CONTENT_TYPE,
      "text/plain; version=0.0.4; charset=utf-8",
    )],
    output,
  )
}

pub(crate) fn event_committed(duplicate: bool) {
  let counter = if duplicate { &DUPLICATES } else { &COMMITTED };
  counter.fetch_add(1, Ordering::Relaxed);
}
pub(crate) fn relay_page_read(watermark: u64, cursor: u64) {
  relay_advanced(watermark, cursor);
  RELAY_LAST_SUCCESS.store(
    std::time::SystemTime::now()
      .duration_since(std::time::UNIX_EPOCH)
      .unwrap_or_default()
      .as_secs(),
    Ordering::Relaxed,
  );
}
pub(crate) fn relay_advanced(watermark: u64, cursor: u64) {
  RELAY_LAG.store(watermark.saturating_sub(cursor), Ordering::Relaxed);
}
pub(crate) fn relay_failed() {
  RELAY_ERRORS.fetch_add(1, Ordering::Relaxed);
}
pub(crate) fn resync_requested() {
  RESYNCS.fetch_add(1, Ordering::Relaxed);
}
