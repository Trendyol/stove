use super::{Backend, Repository};
use crate::error::Result;

mod policy;
pub(super) use policy::{IdleTimeout, SWEEP_BATCH_SIZE};

impl Repository {
  /// Seed once. Runtime Admin changes are shared and survive process restarts.
  pub fn initialize_idle_timeout(&self, seconds: u64) -> Result<()> {
    let timeout = IdleTimeout::new(seconds)?;
    self.with_backend(
      "initialize_idle_timeout",
      "write",
      |backend| match backend {
        Backend::Sqlite(sqlite) => sqlite.initialize_idle_timeout(timeout),
        Backend::Postgres(postgres) => postgres.initialize_idle_timeout(timeout),
      },
    )
  }

  pub fn update_idle_timeout(&self, seconds: u64) -> Result<()> {
    let timeout = IdleTimeout::new(seconds)?;
    self.with_backend("update_idle_timeout", "write", |backend| match backend {
      Backend::Sqlite(sqlite) => sqlite.update_idle_timeout(timeout),
      Backend::Postgres(postgres) => postgres.update_idle_timeout(timeout),
    })
  }

  pub fn expire_idle_runs(&self) -> Result<usize> {
    self.with_backend("expire_idle_runs", "maintenance", |backend| match backend {
      Backend::Sqlite(sqlite) => sqlite.expire_idle_runs(),
      Backend::Postgres(postgres) => postgres.expire_idle_runs(),
    })
  }
}

#[cfg(test)]
pub(in crate::storage::repository) mod tests;
