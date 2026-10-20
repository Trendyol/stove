//! Process-local storage instrumentation backed by the prometheus crate.
#![allow(clippy::cast_precision_loss)]

mod prometheus;
mod samples;
pub use samples::{Capacity, DatabaseDiagnostics, DatabaseMetrics, HealthSample};
#[cfg(test)]
mod tests;
use self::prometheus::Instruments;
use crate::error::{AppError, Result};
use std::cell::{Cell, RefCell};
use std::sync::{
  Mutex,
  atomic::{AtomicBool, Ordering},
};
use std::time::Instant;

thread_local! {
  static WAIT: Cell<f64> = const { Cell::new(0.) };
  static MAINTENANCE: RefCell<Option<PendingMaintenance>> = const { RefCell::new(None) };
}

#[derive(Default)]
struct PendingMaintenance {
  seconds: f64,
  attempted: bool,
  outcome: Outcome,
  deleted: u64,
}
#[derive(Clone, Copy, Default)]
struct Outcome {
  failed: bool,
  timeout: bool,
  busy: bool,
}
impl Outcome {
  fn merge(&mut self, other: Self) {
    self.failed |= other.failed;
    self.timeout |= other.timeout;
    self.busy |= other.busy;
  }
  fn from_error(error: Option<&AppError>) -> Self {
    Self {
      failed: error.is_some(),
      timeout: error.is_some_and(is_timeout),
      busy: error.is_some_and(is_busy),
    }
  }
}
// Defer retention counters until commit, aggregating all applications in one
// transaction. Bounded state and RAII also handle nesting and unwinding.
struct MeasurementScope {
  wait: f64,
  maintenance: Option<PendingMaintenance>,
}
impl MeasurementScope {
  fn enter() -> Self {
    Self {
      wait: WAIT.replace(0.),
      maintenance: MAINTENANCE.replace(Some(PendingMaintenance::default())),
    }
  }
}
impl Drop for MeasurementScope {
  fn drop(&mut self) {
    WAIT.set(self.wait);
    MAINTENANCE.set(self.maintenance.take());
  }
}
struct Waiting<'a> {
  metrics: &'a StorageMetrics,
  role: &'static str,
}
impl Drop for Waiting<'_> {
  fn drop(&mut self) {
    self.metrics.instruments.waiter_finished(self.role);
  }
}
#[derive(Default)]
struct State {
  capacity: Vec<Capacity>,
  health: Vec<HealthSample>,
  diagnostics: DatabaseDiagnostics,
  sampled: Option<Instant>,
}
pub struct StorageMetrics {
  backend: &'static str,
  started_at: chrono::DateTime<chrono::Utc>,
  monitoring: AtomicBool,
  state: Mutex<State>,
  instruments: Instruments,
  // Serialize refresh + gather so concurrent scrapes never restore older gauges.
  exposition: Mutex<()>,
}
impl StorageMetrics {
  pub(crate) fn new(backend: &'static str) -> Self {
    let host = std::env::var("STOVE_INSTANCE_ID")
      .or_else(|_| std::env::var("HOSTNAME"))
      .unwrap_or_else(|_| "stove".into());
    Self::with_instance(
      backend,
      &format!("{host}:{}:{}", std::process::id(), uuid::Uuid::new_v4()),
    )
  }
  fn with_instance(backend: &'static str, instance_id: &str) -> Self {
    let instruments = Instruments::new(instance_id, backend);
    Self {
      backend,
      started_at: chrono::Utc::now(),
      monitoring: AtomicBool::new(false),
      state: Mutex::new(State::default()),
      instruments,
      exposition: Mutex::new(()),
    }
  }
  pub(crate) fn start_once(&self) -> bool {
    !self.monitoring.swap(true, Ordering::Relaxed)
  }
  pub(crate) fn measure<T>(
    &self,
    name: &'static str,
    role: &'static str,
    operation: impl FnOnce() -> Result<T>,
  ) -> Result<T> {
    let _scope = MeasurementScope::enter();
    let started = Instant::now();
    let result = operation();
    let seconds = started.elapsed().as_secs_f64();
    let outcome = Outcome::from_error(result.as_ref().err());
    self
      .instruments
      .observe("operation", name, role, seconds, outcome);
    self.instruments.observe(
      "execution",
      name,
      role,
      (seconds - WAIT.get()).max(0.),
      outcome,
    );
    if let Some(mut pending) = MAINTENANCE.take()
      && pending.attempted
    {
      pending.outcome.merge(outcome);
      self.instruments.observe(
        "maintenance",
        "retention",
        "write",
        pending.seconds,
        pending.outcome,
      );
      if !pending.outcome.failed {
        self.increment("retention_runs_deleted", pending.deleted);
      }
    }
    result
  }
  pub(crate) fn wait<T>(
    &self,
    name: &'static str,
    role: &'static str,
    acquire: impl FnOnce() -> Result<T>,
  ) -> Result<T> {
    self.instruments.waiter_started(role);
    let waiting = Waiting {
      metrics: self,
      role,
    };
    let started = Instant::now();
    let result = acquire();
    let seconds = started.elapsed().as_secs_f64();
    drop(waiting);
    WAIT.set(WAIT.get() + seconds);
    self.instruments.observe(
      "wait",
      name,
      role,
      seconds,
      Outcome::from_error(result.as_ref().err()),
    );
    result
  }
  pub(crate) fn increment(&self, name: &'static str, amount: u64) {
    self.instruments.increment(name, amount);
  }
  pub(crate) fn set_health(&self, health: Vec<HealthSample>) {
    self.state.lock().unwrap().health = health;
  }
  pub(crate) fn set_diagnostics(&self, result: std::result::Result<DatabaseDiagnostics, String>) {
    let mut state = self.state.lock().unwrap();
    match result {
      Ok(mut diagnostics) => {
        diagnostics.collected_at = Some(chrono::Utc::now());
        state.diagnostics = diagnostics;
      }
      Err(error) => {
        state.diagnostics.error = Some(error);
      }
    }
  }
  pub(crate) fn sample(&self, capacity: Vec<Capacity>) {
    let mut state = self.state.lock().unwrap();
    state.capacity = capacity;
    state.sampled = Some(Instant::now());
  }
  #[must_use]
  pub fn snapshot(&self) -> DatabaseMetrics {
    let state = self.state.lock().unwrap();
    let mut capacity = state.capacity.clone();
    for lane in &mut capacity {
      lane.waiters = self.instruments.waiters(&lane.role);
    }
    DatabaseMetrics {
      backend: self.backend.into(),
      started_at: self.started_at,
      sample_age_seconds: state.sampled.map(|at| at.elapsed().as_secs_f64()),
      capacity,
      health: state.health.clone(),
      diagnostics: state.diagnostics.clone(),
      counters: self.instruments.counters(),
    }
  }
  #[must_use]
  pub fn prometheus(&self) -> String {
    let _export = self.exposition.lock().unwrap();
    self.instruments.encode(&self.snapshot())
  }
}
pub(crate) fn retention(operation: impl FnOnce() -> Result<u64>) -> Result<()> {
  let started = Instant::now();
  let result = operation();
  let outcome = Outcome::from_error(result.as_ref().err());
  MAINTENANCE.with(|pending| {
    if let Some(pending) = pending.borrow_mut().as_mut() {
      pending.attempted = true;
      pending.seconds += started.elapsed().as_secs_f64();
      pending.outcome.merge(outcome);
      if let Ok(deleted) = result.as_ref() {
        pending.deleted += deleted;
      }
    }
  });
  result.map(|_| ())
}
fn is_timeout(error: &AppError) -> bool {
  matches!(error, AppError::Diesel(diesel::result::Error::DatabaseError(_, info)) if matches!(info.message(), "canceling statement due to statement timeout" | "canceling statement due to lock timeout"))
    || matches!(error, AppError::DatabasePool(_))
    || matches!(error, AppError::Postgres(e) if e.code().is_some_and(|c| c.code() == "57014" || c.code() == "55P03"))
}
fn is_busy(error: &AppError) -> bool {
  matches!(error, AppError::Database(rusqlite::Error::SqliteFailure(e, _)) if matches!(e.code, rusqlite::ErrorCode::DatabaseBusy | rusqlite::ErrorCode::DatabaseLocked))
    || matches!(error, AppError::Diesel(diesel::result::Error::DatabaseError(_, info)) if matches!(info.message(), "database is locked" | "database table is locked" | "database is busy"))
}
