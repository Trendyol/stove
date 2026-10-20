//! Cached database samples shared by both backend collectors.
use std::collections::BTreeMap;
use std::time::Instant;

#[derive(Clone, Default)]
pub struct Capacity {
  pub role: String,
  pub maximum: u32,
  pub connections: u32,
  pub idle: u32,
  pub busy: u32,
  pub waiters: u64,
}
#[derive(Clone, Default)]
pub struct DatabaseDiagnostics {
  pub collected_at: Option<chrono::DateTime<chrono::Utc>>,
  pub error: Option<String>,
  pub database_bytes: Option<u64>,
  pub wal_bytes: Option<u64>,
  pub disk_available_bytes: Option<u64>,
  pub free_page_bytes: Option<u64>,
  pub blocked_sessions: Option<i64>,
  pub oldest_transaction_seconds: Option<f64>,
  pub deadlocks: Option<i64>,
  pub dead_tuples: Option<i64>,
  pub last_autovacuum_at: Option<f64>,
  pub checkpoint_probe_busy: Option<i64>,
  pub checkpoint_probe_up: Option<bool>,
  pub wal_frames: Option<i64>,
  pub checkpointed_frames: Option<i64>,
  pub stats_reset_at: Option<f64>,
  pub notes: Vec<String>,
}
#[derive(Clone)]
pub struct HealthSample {
  pub role: String,
  pub healthy: bool,
  pub duration_ms: f64,
  pub checked_at: chrono::DateTime<chrono::Utc>,
}
#[derive(Clone)]
pub struct DatabaseMetrics {
  pub backend: String,
  pub started_at: chrono::DateTime<chrono::Utc>,
  pub sample_age_seconds: Option<f64>,
  pub capacity: Vec<Capacity>,
  pub health: Vec<HealthSample>,
  pub diagnostics: DatabaseDiagnostics,
  pub counters: BTreeMap<String, u64>,
}

impl Capacity {
  pub(crate) fn new(role: &str, maximum: u32, connections: u32, idle: u32) -> Self {
    Self {
      role: role.into(),
      maximum,
      connections,
      idle,
      busy: connections.saturating_sub(idle),
      waiters: 0,
    }
  }
}

impl HealthSample {
  pub(crate) fn probe(role: &str, check: impl FnOnce() -> bool) -> Self {
    let started = Instant::now();
    let healthy = check();
    Self {
      role: role.into(),
      healthy,
      duration_ms: started.elapsed().as_secs_f64() * 1000.,
      checked_at: chrono::Utc::now(),
    }
  }
}
