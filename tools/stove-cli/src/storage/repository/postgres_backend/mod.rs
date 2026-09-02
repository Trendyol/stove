use std::sync::{Mutex, MutexGuard};

use diesel::prelude::*;

use crate::error::Result;

mod admin;
mod database;
mod distributed;
mod reads;
mod writes;

pub(super) struct PostgresBackend {
  write: Mutex<PgConnection>,
  read: Mutex<PgConnection>,
  database_url: String,
}

impl PostgresBackend {
  pub(super) fn connect(database_url: &str, default_retention: usize) -> Result<Self> {
    let connections = database::open(database_url, default_retention)?;
    Ok(Self {
      write: Mutex::new(connections.write),
      read: Mutex::new(connections.read),
      database_url: database_url.to_string(),
    })
  }

  pub(super) fn database_url(&self) -> &str {
    &self.database_url
  }

  fn lock_write(&self) -> MutexGuard<'_, PgConnection> {
    self.write.lock().expect("PostgreSQL write lock poisoned")
  }

  fn lock_read(&self) -> MutexGuard<'_, PgConnection> {
    self.read.lock().expect("PostgreSQL read lock poisoned")
  }
}

#[cfg(test)]
mod tests;
