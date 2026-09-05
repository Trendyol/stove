//! Thread-safe storage facade for dashboard runs, tests, entries, spans, and
//! snapshots.
//!
//! Writes and reads use separate database connections so the UI can keep
//! polling while ingestion is busy. Each side is serialized through its own
//! mutex because Diesel connections are not `Sync`.
//!
//! This module owns backend selection and exposes backend-neutral operations.
//! Engine-specific connections, queries, writes, and administration live in
//! parallel `sqlite_backend/` and `postgres_backend/` modules.

mod admin;
mod distributed;
mod explorer;
mod mapping;
pub mod pagination;
mod postgres_backend;
mod reads;
pub(crate) mod replay;
mod sqlite_backend;
mod write_models;
mod writes;

#[cfg(test)]
use std::sync::MutexGuard;
use std::sync::atomic::AtomicUsize;
use std::sync::atomic::Ordering;

use crate::error::Result;
enum Backend {
  Sqlite(sqlite_backend::SqliteBackend),
  Postgres(Box<postgres_backend::PostgresBackend>),
}

pub struct Repository {
  backend: Backend,
  replay_admission: std::sync::Arc<tokio::sync::Semaphore>,
  read_admission: std::sync::Arc<tokio::sync::Semaphore>,
  pub(crate) stream_admission: std::sync::Arc<tokio::sync::Semaphore>,
  retention_runs_per_app: AtomicUsize,
}

impl Repository {
  /// Configure admission before sharing the repository with request handlers.
  pub fn configure_admission(&mut self, reads: usize, replay: usize, streams: usize) -> Result<()> {
    if [reads, replay, streams]
      .iter()
      .any(|capacity| !(1..=65_536).contains(capacity))
    {
      return Err(crate::error::AppError::Startup(
        "admission capacities must be between 1 and 65536".into(),
      ));
    }
    self.read_admission = std::sync::Arc::new(tokio::sync::Semaphore::new(reads));
    self.replay_admission = std::sync::Arc::new(tokio::sync::Semaphore::new(replay));
    self.stream_admission = std::sync::Arc::new(tokio::sync::Semaphore::new(streams));
    Ok(())
  }

  pub fn connect_sqlite(database_path: &str, retention_runs_per_app: usize) -> Result<Self> {
    Ok(Self {
      stream_admission: std::sync::Arc::new(tokio::sync::Semaphore::new(64)),
      replay_admission: std::sync::Arc::new(tokio::sync::Semaphore::new(16)),
      read_admission: std::sync::Arc::new(tokio::sync::Semaphore::new(64)),
      backend: Backend::Sqlite(sqlite_backend::SqliteBackend::connect(database_path)?),
      retention_runs_per_app: AtomicUsize::new(retention_runs_per_app),
    })
  }

  pub fn connect_postgres(database_url: &str, retention_runs_per_app: usize) -> Result<Self> {
    Self::connect_postgres_with_pools(database_url, retention_runs_per_app, 4, 4, 2)
  }

  pub fn connect_postgres_with_pools(
    database_url: &str,
    retention_runs_per_app: usize,
    writers: usize,
    readers: usize,
    replay_readers: usize,
  ) -> Result<Self> {
    if [writers, readers, replay_readers]
      .iter()
      .any(|size| !(1..=64).contains(size))
    {
      return Err(crate::error::AppError::Startup(
        "PostgreSQL pool sizes must be between 1 and 64".into(),
      ));
    }
    Ok(Self {
      stream_admission: std::sync::Arc::new(tokio::sync::Semaphore::new(64)),
      replay_admission: std::sync::Arc::new(tokio::sync::Semaphore::new(16)),
      read_admission: std::sync::Arc::new(tokio::sync::Semaphore::new(64)),
      backend: Backend::Postgres(Box::new(run_blocking(|| {
        postgres_backend::PostgresBackend::connect(
          database_url,
          retention_runs_per_app,
          writers,
          readers,
          replay_readers,
        )
      })?)),
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
  fn with_backend<T, F>(&self, operation: F) -> T
  where
    T: Send,
    F: FnOnce(&Backend) -> T + Send,
  {
    run_blocking(|| operation(&self.backend))
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
