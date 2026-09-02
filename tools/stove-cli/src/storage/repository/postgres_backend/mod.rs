use std::sync::{Mutex, MutexGuard};

use native_tls::TlsConnector;
use postgres::{Client, NoTls};
use postgres_native_tls::MakeTlsConnector;

use crate::error::Result;
use crate::storage::migrations::postgres::run_migrations;

mod admin;
mod mapping;
mod reads;
mod writes;

pub(super) struct PostgresBackend {
  write: Mutex<Client>,
  read: Mutex<Client>,
}

impl PostgresBackend {
  pub(super) fn connect(database_url: &str) -> Result<Self> {
    let mut write = connect(database_url)?;
    run_migrations(&mut write)?;
    let read = connect(database_url)?;
    Ok(Self {
      write: Mutex::new(write),
      read: Mutex::new(read),
    })
  }

  fn lock_write(&self) -> MutexGuard<'_, Client> {
    self.write.lock().expect("PostgreSQL write lock poisoned")
  }

  fn lock_read(&self) -> MutexGuard<'_, Client> {
    self.read.lock().expect("PostgreSQL read lock poisoned")
  }
}

fn connect(database_url: &str) -> Result<Client> {
  if database_url.contains("sslmode=disable") {
    return Ok(Client::connect(database_url, NoTls)?);
  }
  let connector = TlsConnector::builder().build()?;
  Ok(Client::connect(
    database_url,
    MakeTlsConnector::new(connector),
  )?)
}

#[cfg(test)]
mod tests;
