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
  explorer: Mutex<Connection>,
}

impl SqliteBackend {
  pub(super) fn connect(path: &str) -> Result<Self> {
    Self::new(SqliteDatabase::open(path)?)
  }

  fn new(database: SqliteDatabase) -> Result<Self> {
    let explorer = database.open_driver_peer()?;
    let read = database.open_peer()?;
    Ok(Self {
      write: Arc::new(Mutex::new(database)),
      read: Arc::new(Mutex::new(read)),
      explorer: Mutex::new(explorer),
    })
  }

  pub(super) fn lock_write(&self) -> MutexGuard<'_, SqliteDatabase> {
    self.write.lock().expect("write database lock poisoned")
  }

  pub(super) fn lock_read(&self) -> MutexGuard<'_, SqliteDatabase> {
    self.read.lock().expect("read database lock poisoned")
  }

  fn lock_explorer(&self) -> MutexGuard<'_, Connection> {
    self
      .explorer
      .lock()
      .expect("explorer database lock poisoned")
  }
}
