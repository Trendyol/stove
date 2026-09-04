use std::sync::{Mutex, MutexGuard};

use diesel::prelude::*;

use crate::error::Result;

mod admin;
mod database;
mod distributed;
mod explorer;
mod reads;
mod writes;

pub(super) struct PostgresBackend {
  write: Mutex<PgConnection>,
  read: Mutex<PgConnection>,
  explorer: Mutex<postgres::Client>,
  database_url: String,
}

impl PostgresBackend {
  pub(super) fn connect(database_url: &str, default_retention: usize) -> Result<Self> {
    let connections = database::open(database_url, default_retention)?;
    Ok(Self {
      write: Mutex::new(connections.write),
      read: Mutex::new(connections.read),
      explorer: Mutex::new(connections.explorer),
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

  fn lock_explorer(&self) -> MutexGuard<'_, postgres::Client> {
    self
      .explorer
      .lock()
      .expect("PostgreSQL explorer lock poisoned")
  }
}

#[cfg(test)]
mod tests;
