//! One definition per diagnostic gauge, shared by registration and refresh.
use super::DatabaseMetrics;
use chrono::{DateTime, Utc};

type GaugeDefinition = (&'static str, fn(&DatabaseMetrics) -> Option<f64>);

pub(super) const GAUGES: &[GaugeDefinition] = &[
  ("sample_age_seconds", |s| s.sample_age_seconds),
  ("diagnostics_age_seconds", |s| {
    s.diagnostics.collected_at.map(age)
  }),
  ("diagnostics_up", |s| {
    Some(f64::from(
      s.diagnostics.error.is_none() && s.diagnostics.collected_at.is_some(),
    ))
  }),
  ("process_start_timestamp_seconds", |s| {
    Some(s.started_at.timestamp() as f64)
  }),
  ("database_bytes", |s| {
    s.diagnostics.database_bytes.map(|v| v as f64)
  }),
  ("wal_bytes", |s| s.diagnostics.wal_bytes.map(|v| v as f64)),
  ("disk_available_bytes", |s| {
    s.diagnostics.disk_available_bytes.map(|v| v as f64)
  }),
  ("free_page_bytes", |s| {
    s.diagnostics.free_page_bytes.map(|v| v as f64)
  }),
  ("blocked_sessions", |s| {
    s.diagnostics.blocked_sessions.map(|v| v as f64)
  }),
  ("oldest_transaction_seconds", |s| {
    s.diagnostics.oldest_transaction_seconds
  }),
  ("session_diagnostics_available", |s| {
    (s.backend == "postgresql").then_some(f64::from(s.diagnostics.blocked_sessions.is_some()))
  }),
  ("last_autovacuum_timestamp_seconds", |s| {
    s.diagnostics.last_autovacuum_at
  }),
  ("postgres_stats_reset_timestamp_seconds", |s| {
    s.diagnostics.stats_reset_at
  }),
  ("checkpoint_probe_up", |s| {
    s.diagnostics.checkpoint_probe_up.map(f64::from)
  }),
  ("checkpoint_probe_busy", |s| {
    s.diagnostics.checkpoint_probe_busy.map(|v| v as f64)
  }),
  ("dead_tuples", |s| {
    s.diagnostics.dead_tuples.map(|v| v as f64)
  }),
  ("wal_frames", |s| s.diagnostics.wal_frames.map(|v| v as f64)),
  ("checkpointed_frames", |s| {
    s.diagnostics.checkpointed_frames.map(|v| v as f64)
  }),
];

pub(super) fn age(timestamp: DateTime<Utc>) -> f64 {
  (Utc::now() - timestamp).num_milliseconds().max(0) as f64 / 1000.
}
