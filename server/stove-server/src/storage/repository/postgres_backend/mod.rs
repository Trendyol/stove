use std::sync::{Mutex, MutexGuard};

use diesel::connection::SimpleConnection;
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
  pub(super) fn check_health(&self) -> Result<()> {
    self
      .lock_read()?
      .batch_execute("SELECT id FROM runs LIMIT 1")?;
    self
      .lock_write()?
      .batch_execute("SELECT id FROM runs LIMIT 1")?;
    self
      .lock_explorer()?
      .batch_execute("SELECT id FROM runs LIMIT 1")?;
    Ok(())
  }

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

  fn lock_write(&self) -> Result<MutexGuard<'_, PgConnection>> {
    self.lock_connection(&self.write, "write")
  }

  fn lock_read(&self) -> Result<MutexGuard<'_, PgConnection>> {
    self.lock_connection(&self.read, "read")
  }

  // Validate before starting work so an idle timeout or database restart does not
  // permanently poison a lane. Never replay the operation itself: a failed write
  // or COMMIT can have an ambiguous outcome.
  fn lock_connection<'a>(
    &self,
    connection: &'a Mutex<PgConnection>,
    lane: &str,
  ) -> Result<MutexGuard<'a, PgConnection>> {
    let mut connection = connection
      .lock()
      .expect("PostgreSQL connection lock poisoned");
    if connection.batch_execute("SELECT 1").is_err() {
      tracing::warn!(
        lane,
        "Reconnecting PostgreSQL connection after failed health probe"
      );
      *connection = PgConnection::establish(&self.database_url)?;
    }
    Ok(connection)
  }

  fn lock_explorer(&self) -> Result<MutexGuard<'_, postgres::Client>> {
    let mut connection = self
      .explorer
      .lock()
      .expect("PostgreSQL explorer lock poisoned");
    if connection.batch_execute("SELECT 1").is_err() {
      tracing::warn!("Reconnecting PostgreSQL explorer after failed health probe");
      *connection = database::connect_driver(&self.database_url)?;
    }
    Ok(connection)
  }
}

#[cfg(test)]
mod tests;
