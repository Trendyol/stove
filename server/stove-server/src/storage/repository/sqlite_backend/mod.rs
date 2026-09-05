use crate::metrics::{DatabaseOperation, database_acquire};
use std::sync::{Arc, Mutex, MutexGuard};

use rusqlite::Connection;

use crate::error::Result;

pub(super) mod database;
use database::SqliteDatabase;

pub(super) mod admin;
pub(super) mod distributed;
pub(super) mod explorer;
pub(super) mod reads;
pub(super) mod writes;

pub(super) struct SqliteBackend {
  write: Arc<Mutex<SqliteDatabase>>,
  read: Arc<Mutex<SqliteDatabase>>,
  replay: Arc<Mutex<SqliteDatabase>>,
  explorer: Mutex<Connection>,
  in_memory: bool,
}

impl SqliteBackend {
  pub(super) fn connect(path: &str) -> Result<Self> {
    Self::new(SqliteDatabase::open(path)?)
  }

  fn new(database: SqliteDatabase) -> Result<Self> {
    let explorer = database.open_driver_peer()?;
    let in_memory = database.is_in_memory();
    // Shared in-memory SQLite has table locks, not WAL snapshots. Use one
    // serialized connection there so replay cannot cause SQLITE_LOCKED writes.
    let peers = if in_memory {
      None
    } else {
      Some((database.open_peer()?, database.open_peer()?))
    };
    let write = Arc::new(Mutex::new(database));
    let (read, replay) = peers.map_or_else(
      || (write.clone(), write.clone()),
      |(read, replay)| (Arc::new(Mutex::new(read)), Arc::new(Mutex::new(replay))),
    );
    Ok(Self {
      write,
      read,
      replay,
      explorer: Mutex::new(explorer),
      in_memory,
    })
  }

  pub(super) fn lock_write(&self) -> MutexGuard<'_, SqliteDatabase> {
    database_acquire(DatabaseOperation::SqliteWriteWait, || {
      self.write.lock().expect("write database lock poisoned")
    })
  }

  pub(super) fn lock_read(&self) -> MutexGuard<'_, SqliteDatabase> {
    database_acquire(DatabaseOperation::SqliteReadWait, || {
      self.read.lock().expect("read database lock poisoned")
    })
  }

  fn lock_replay(&self) -> MutexGuard<'_, SqliteDatabase> {
    database_acquire(DatabaseOperation::SqliteReplayWait, || {
      self.replay.lock().expect("replay database lock poisoned")
    })
  }

  fn lock_explorer(&self) -> MutexGuard<'_, Connection> {
    database_acquire(DatabaseOperation::SqliteExplorerWait, || {
      self
        .explorer
        .lock()
        .expect("explorer database lock poisoned")
    })
  }
}
