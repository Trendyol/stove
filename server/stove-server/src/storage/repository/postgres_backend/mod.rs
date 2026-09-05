use std::sync::{Mutex, MutexGuard};

use diesel::prelude::*;

use crate::error::Result;

mod admin;
mod database;
mod distributed;
mod explorer;
mod pool;
mod reads;
mod writes;

pub(super) struct PostgresBackend {
  write: pool::Pool,
  read: pool::Pool,
  replay: pool::Pool,
  explorer: Mutex<postgres::Client>,
  database_url: String,
}

impl PostgresBackend {
  pub(super) fn connect(
    database_url: &str,
    default_retention: usize,
    writers: usize,
    readers: usize,
    replay_readers: usize,
  ) -> Result<Self> {
    let connections = database::open(database_url, default_retention)?;
    Ok(Self {
      replay: pool::Pool::new(
        PgConnection::establish(database_url)?,
        database_url,
        replay_readers,
      )?,
      write: pool::Pool::new(connections.write, database_url, writers)?,
      read: pool::Pool::new(connections.read, database_url, readers)?,
      explorer: Mutex::new(connections.explorer),
      database_url: database_url.to_string(),
    })
  }

  pub(super) fn database_url(&self) -> &str {
    &self.database_url
  }

  fn lock_write(&self) -> pool::Lease<'_> {
    self.write.get()
  }

  fn lock_read(&self) -> pool::Lease<'_> {
    self.read.get()
  }

  fn lock_replay(&self) -> pool::Lease<'_> {
    self.replay.get()
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
