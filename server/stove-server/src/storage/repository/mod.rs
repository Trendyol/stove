//! Thread-safe storage facade for dashboard runs, tests, entries, spans, and
//! snapshots.
//!
//! Writes and reads use separate database capacity so the UI can keep polling
//! while ingestion is busy. SQLite serializes each connection through a mutex;
//! PostgreSQL uses separate connection pools for writes, reads, and the explorer.
//!
//! This module owns backend selection and exposes backend-neutral operations.
//! Engine-specific connections, queries, writes, and administration live in
//! parallel `sqlite_backend/` and `postgres_backend/` modules.

mod admin;
mod distributed;
mod evidence_sql;
mod explorer;
mod mapping;
mod monitoring;
mod postgres_backend;
mod reads;
mod sqlite_backend;
mod write_models;
mod writes;

#[cfg(test)]
use std::sync::MutexGuard;
use std::sync::atomic::AtomicUsize;
use std::sync::atomic::Ordering;

use crate::error::Result;
enum Backend {
  Sqlite(Box<sqlite_backend::SqliteBackend>),
  Postgres(Box<postgres_backend::PostgresBackend>),
}

pub struct Repository {
  backend: Backend,
  pub metrics: std::sync::Arc<crate::storage::metrics::StorageMetrics>,
  retention_runs_per_app: AtomicUsize,
}

impl Repository {
  /// Probe both dashboard and ingestion connections without modifying stored data.
  pub fn check_health(&self) -> Result<()> {
    self.with_backend("check_health", "probe", |backend| match backend {
      Backend::Sqlite(sqlite) => sqlite.check_health(),
      Backend::Postgres(postgres) => postgres.check_health(),
    })
  }

  pub fn connect_sqlite(database_path: &str, retention_runs_per_app: usize) -> Result<Self> {
    let backend = sqlite_backend::SqliteBackend::connect(database_path)?;
    let metrics = backend.metrics.clone();
    Ok(Self {
      metrics,
      backend: Backend::Sqlite(Box::new(backend)),
      retention_runs_per_app: AtomicUsize::new(retention_runs_per_app),
    })
  }

  pub fn connect_postgres(database_url: &str, retention_runs_per_app: usize) -> Result<Self> {
    let backend = run_blocking(|| {
      postgres_backend::PostgresBackend::connect(database_url, retention_runs_per_app)
    })?;
    let metrics = backend.metrics.clone();
    Ok(Self {
      metrics,
      backend: Backend::Postgres(Box::new(backend)),
      retention_runs_per_app: AtomicUsize::new(retention_runs_per_app),
    })
  }

  #[must_use]
  pub fn backend_kind(&self) -> &'static str {
    match self.backend {
      Backend::Sqlite(_) => "sqlite",
      Backend::Postgres(_) => "postgresql",
    }
  }

  #[must_use]
  pub fn retention_runs_per_app(&self) -> usize {
    match &self.backend {
      Backend::Sqlite(_) => self.retention_runs_per_app.load(Ordering::Relaxed),
      Backend::Postgres(postgres) => run_blocking(|| postgres.retention_runs_per_app())
        .unwrap_or_else(|_| self.retention_runs_per_app.load(Ordering::Relaxed)),
    }
  }

  pub fn set_retention_runs_per_app(&self, retention_runs_per_app: usize) {
    self
      .retention_runs_per_app
      .store(retention_runs_per_app, Ordering::Relaxed);
  }

  /// Execute one synchronous backend operation without occupying a Tokio core worker.
  fn with_backend<T, F>(&self, name: &'static str, role: &'static str, operation: F) -> Result<T>
  where
    T: Send,
    F: FnOnce(&Backend) -> Result<T> + Send,
  {
    run_blocking(|| {
      self
        .metrics
        .measure(name, role, || operation(&self.backend))
    })
  }

  #[cfg(test)]
  pub(in crate::storage::repository) fn lock_write_db(
    &self,
  ) -> MutexGuard<'_, sqlite_backend::database::SqliteDatabase> {
    let Backend::Sqlite(sqlite) = &self.backend else {
      panic!("SQLite test connection requested from PostgreSQL repository")
    };
    sqlite.lock_write()
  }

  #[cfg(test)]
  pub(crate) fn with_write_db_locked<T>(&self, operation: impl FnOnce() -> T) -> T {
    let _guard = match &self.backend {
      Backend::Sqlite(sqlite) => sqlite.lock_write(),
      Backend::Postgres(_) => panic!("SQLite test connection requested from PostgreSQL repository"),
    };
    operation()
  }

  #[cfg(test)]
  pub(crate) fn with_read_db_locked<T>(&self, operation: impl FnOnce() -> T) -> T {
    let _guard = match &self.backend {
      Backend::Sqlite(sqlite) => sqlite.lock_read(),
      Backend::Postgres(_) => panic!("SQLite test connection requested from PostgreSQL repository"),
    };
    operation()
  }
}

fn run_blocking<T, F>(operation: F) -> T
where
  T: Send,
  F: FnOnce() -> T + Send,
{
  match tokio::runtime::Handle::try_current().map(|handle| handle.runtime_flavor()) {
    Ok(tokio::runtime::RuntimeFlavor::MultiThread) => tokio::task::block_in_place(operation),
    Ok(_) => std::thread::scope(|scope| {
      scope
        .spawn(operation)
        .join()
        .unwrap_or_else(|payload| std::panic::resume_unwind(payload))
    }),
    Err(_) => operation(),
  }
}

#[cfg(test)]
mod tests;
