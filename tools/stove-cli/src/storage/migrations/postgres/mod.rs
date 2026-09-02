use postgres::Client;
use tracing::info;

use super::Migration;
use crate::error::Result;

const MIGRATIONS: &[Migration] = &[
  Migration::new(
    1,
    "V1__initial_schema",
    include_str!("V1__initial_schema.sql"),
  ),
  Migration::new(
    2,
    "V2__run_query_indexes",
    include_str!("V2__run_query_indexes.sql"),
  ),
];

/// Apply pending `PostgreSQL` migrations transactionally.
///
/// Locking the migration table serializes concurrent server startups. Each
/// migration and its version record are committed together, so a failed
/// migration is safe to retry on the next startup.
pub(crate) fn run_migrations(client: &mut Client) -> Result<()> {
  client.batch_execute(
    "CREATE TABLE IF NOT EXISTS schema_migrations (
      version BIGINT PRIMARY KEY,
      name TEXT NOT NULL,
      applied_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
    )",
  )?;

  for migration in MIGRATIONS {
    let mut transaction = client.transaction()?;
    transaction.batch_execute("LOCK TABLE schema_migrations IN EXCLUSIVE MODE")?;
    let current_version: i64 = transaction
      .query_one(
        "SELECT COALESCE(MAX(version), 0) FROM schema_migrations",
        &[],
      )?
      .get(0);

    if migration.version <= current_version {
      transaction.rollback()?;
      continue;
    }

    transaction.batch_execute(migration.sql)?;
    transaction.execute(
      "INSERT INTO schema_migrations (version, name) VALUES ($1, $2)",
      &[&migration.version, &migration.name],
    )?;
    transaction.commit()?;
    info!(
      version = migration.version,
      name = migration.name,
      database = "postgresql",
      "Applied migration"
    );
  }
  Ok(())
}

#[cfg(test)]
pub(crate) const fn migration_count() -> usize {
  MIGRATIONS.len()
}
