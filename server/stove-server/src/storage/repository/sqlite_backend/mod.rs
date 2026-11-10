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
    // Shared-cache in-memory SQLite cannot use WAL and returns SQLITE_LOCKED
    // immediately when another connection holds a conflicting table lock.
    // Use one connection for reads and writes in that mode.
    let read = if database.disk_path().is_some() {
      Some(database.open_peer()?)
    } else {
      None
    };
    let monitor = database.open_driver_peer()?;
    monitor.busy_timeout(std::time::Duration::from_millis(100))?;
    let path = database.disk_path();
    let write = Arc::new(Mutex::new(database));
    let read = read.map_or_else(|| write.clone(), |db| Arc::new(Mutex::new(db)));
    Ok(Self {
      metrics: std::sync::Arc::new(crate::storage::metrics::StorageMetrics::new("sqlite")),
      write,
      read,
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

#[cfg(test)]
mod tests {
  use super::SqliteBackend;
  use diesel::connection::SimpleConnection;
  use std::sync::{Arc, mpsc};
  use std::time::Duration;

  #[test]
  fn in_memory_reads_serialize_with_writes() {
    let backend = Arc::new(SqliteBackend::connect(":memory:").unwrap());
    let mut reader = backend.lock_read();
    reader
      .conn()
      .batch_execute("BEGIN; SELECT id FROM runs LIMIT 1;")
      .unwrap();

    let writer = backend.clone();
    let (started_tx, started_rx) = mpsc::channel();
    let (done_tx, done_rx) = mpsc::channel();
    let thread = std::thread::spawn(move || {
      started_tx.send(()).unwrap();
      let result = writer
        .lock_write()
        .conn()
        .batch_execute("UPDATE runs SET app_name = app_name");
      done_tx.send(result).unwrap();
    });
    started_rx.recv().unwrap();
    assert!(matches!(
      done_rx.recv_timeout(Duration::from_millis(50)),
      Err(mpsc::RecvTimeoutError::Timeout)
    ));
    reader.conn().batch_execute("COMMIT").unwrap();
    drop(reader);
    done_rx
      .recv_timeout(Duration::from_secs(1))
      .unwrap()
      .unwrap();
    thread.join().unwrap();
  }
}
