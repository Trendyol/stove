use std::sync::{Arc, Mutex, MutexGuard};

use crate::error::Result;

pub(super) mod database;
use database::SqliteDatabase;

pub(super) mod admin;
pub(super) mod distributed;
pub(super) mod reads;
pub(super) mod writes;

pub(super) struct SqliteBackend {
  write: Arc<Mutex<SqliteDatabase>>,
  read: Arc<Mutex<SqliteDatabase>>,
}

impl SqliteBackend {
  pub(super) fn connect(path: &str) -> Result<Self> {
    Ok(Self::new(SqliteDatabase::open(path)?))
  }

  fn new(database: SqliteDatabase) -> Self {
    let read = database
      .open_peer()
      .expect("peer database connection should open for repository reads");
    Self {
      write: Arc::new(Mutex::new(database)),
      read: Arc::new(Mutex::new(read)),
    }
  }

  pub(super) fn lock_write(&self) -> MutexGuard<'_, SqliteDatabase> {
    self.write.lock().expect("write database lock poisoned")
  }

  pub(super) fn lock_read(&self) -> MutexGuard<'_, SqliteDatabase> {
    self.read.lock().expect("read database lock poisoned")
  }
}
