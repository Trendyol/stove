use std::sync::{Arc, Mutex, MutexGuard};

use rusqlite::Connection;

use crate::error::Result;

pub(super) mod database;
use database::SqliteDatabase;

pub(super) mod admin;
pub(super) mod distributed;
pub(super) mod explorer;
pub(super) mod lifecycle;
mod monitoring;
mod profiles;
pub(super) mod reads;
mod top_filter;
pub(super) mod writes;

pub(super) struct SqliteBackend {
  pub(super) metrics: std::sync::Arc<crate::storage::metrics::StorageMetrics>,
  write: Arc<Mutex<SqliteDatabase>>,
  read: Arc<Mutex<SqliteDatabase>>,
  explorer: Mutex<Connection>,
  monitor: Mutex<Connection>,
  path: Option<String>,
}

impl SqliteBackend {
  pub(super) fn check_health(&self) -> Result<()> {
    use diesel::connection::SimpleConnection;
    self
      .lock_read()
      .conn()
      .batch_execute("SELECT id FROM runs LIMIT 1")?;
    self
      .lock_write()
      .conn()
      .batch_execute("SELECT id FROM runs LIMIT 1")?;
    Ok(())
  }

  pub(super) fn connect(path: &str) -> Result<Self> {
    Self::new(SqliteDatabase::open(path)?)
  }

  fn new(database: SqliteDatabase) -> Result<Self> {
    let explorer = database.open_driver_peer()?;
    let read = database.open_peer()?;
    let monitor = database.open_driver_peer()?;
    monitor.busy_timeout(std::time::Duration::from_millis(100))?;
    let path = database.disk_path();
    Ok(Self {
      metrics: std::sync::Arc::new(crate::storage::metrics::StorageMetrics::new("sqlite")),
      write: Arc::new(Mutex::new(database)),
      read: Arc::new(Mutex::new(read)),
      explorer: Mutex::new(explorer),
      monitor: Mutex::new(monitor),
      path,
    })
  }

  pub(super) fn lock_write(&self) -> MutexGuard<'_, SqliteDatabase> {
    self.lock_connection("write", &self.write)
  }

  pub(super) fn lock_read(&self) -> MutexGuard<'_, SqliteDatabase> {
    self.lock_connection("read", &self.read)
  }

  fn lock_explorer(&self) -> MutexGuard<'_, Connection> {
    self.lock_connection("explorer", &self.explorer)
  }

  fn lock_connection<'a, T>(
    &self,
    role: &'static str,
    connection: &'a Mutex<T>,
  ) -> MutexGuard<'a, T> {
    self
      .metrics
      .wait("mutex", role, || {
        Ok(
          connection
            .lock()
            .unwrap_or_else(|_| panic!("{role} database lock poisoned")),
        )
      })
      .unwrap()
  }
}
