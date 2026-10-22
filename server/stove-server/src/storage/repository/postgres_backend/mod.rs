use database::{DieselConnection, DieselPool, ExplorerConnection, ExplorerPool};
use diesel::connection::SimpleConnection;

use crate::error::Result;

mod admin;
mod database;
mod distributed;
mod explorer;
pub(super) mod lifecycle;
mod monitoring;
mod reads;
mod top_filter;
mod writes;

pub(super) struct PostgresBackend {
  pub(super) metrics: std::sync::Arc<crate::storage::metrics::StorageMetrics>,
  write: DieselPool,
  read: DieselPool,
  explorer: ExplorerPool,
  database_url: String,
}

impl PostgresBackend {
  pub(super) fn check_health(&self) -> Result<()> {
    self
      .checkout_read()?
      .batch_execute("SELECT id FROM runs LIMIT 1")?;
    self
      .checkout_write()?
      .batch_execute("SELECT id FROM runs LIMIT 1")?;
    self
      .checkout_explorer()?
      .batch_execute("SELECT id FROM runs LIMIT 1")?;
    Ok(())
  }

  pub(super) fn connect(database_url: &str, default_retention: usize) -> Result<Self> {
    let connections = database::open(database_url, default_retention)?;
    Ok(Self {
      metrics: std::sync::Arc::new(crate::storage::metrics::StorageMetrics::new("postgresql")),
      write: connections.write,
      read: connections.read,
      explorer: connections.explorer,
      database_url: database_url.to_string(),
    })
  }

  pub(super) fn database_url(&self) -> &str {
    &self.database_url
  }

  // The pool validates and replaces connections before checkout. Operations are
  // never replayed: a failed write or COMMIT can have an ambiguous outcome.
  fn checkout_write(&self) -> Result<DieselConnection> {
    self
      .metrics
      .wait("checkout", "write", || Ok(self.write.get()?))
  }

  fn checkout_read(&self) -> Result<DieselConnection> {
    self
      .metrics
      .wait("checkout", "read", || Ok(self.read.get()?))
  }

  fn checkout_explorer(&self) -> Result<ExplorerConnection> {
    self
      .metrics
      .wait("checkout", "explorer", || Ok(self.explorer.get()?))
  }
}

#[cfg(test)]
mod tests;
