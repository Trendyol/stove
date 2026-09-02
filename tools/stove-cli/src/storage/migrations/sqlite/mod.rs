use rusqlite::Connection;
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
    "V2__run_stove_version",
    include_str!("V2__run_stove_version.sql"),
  ),
  Migration::new(3, "V3__test_path", include_str!("V3__test_path.sql")),
  Migration::new(
    4,
    "V4__mock_interactions",
    include_str!("V4__mock_interactions.sql"),
  ),
  Migration::new(
    5,
    "V5__mock_interaction_metadata",
    include_str!("V5__mock_interaction_metadata.sql"),
  ),
  Migration::new(
    6,
    "V6__entry_assertion_correlation",
    include_str!("V6__entry_assertion_correlation.sql"),
  ),
  Migration::new(7, "V7__run_metadata", include_str!("V7__run_metadata.sql")),
];

/// Apply pending `SQLite` migrations transactionally.
pub(crate) fn run_migrations(connection: &Connection) -> Result<()> {
  connection.execute_batch(
    "CREATE TABLE IF NOT EXISTS schema_migrations (
      version INTEGER PRIMARY KEY,
      name TEXT NOT NULL,
      applied_at TEXT NOT NULL DEFAULT (datetime('now'))
    );",
  )?;

  let current_version: i64 = connection.query_row(
    "SELECT COALESCE(MAX(version), 0) FROM schema_migrations",
    [],
    |row| row.get(0),
  )?;

  for migration in MIGRATIONS {
    if migration.version <= current_version {
      continue;
    }
    let transaction = connection.unchecked_transaction()?;
    transaction.execute_batch(migration.sql)?;
    transaction.execute(
      "INSERT INTO schema_migrations (version, name) VALUES (?1, ?2)",
      rusqlite::params![migration.version, migration.name],
    )?;
    transaction.commit()?;
    info!(
      version = migration.version,
      name = migration.name,
      database = "sqlite",
      "Applied migration"
    );
  }
  Ok(())
}

#[cfg(test)]
pub(crate) const fn migration_count() -> usize {
  MIGRATIONS.len()
}

#[cfg(test)]
pub(crate) fn initial_schema() -> &'static str {
  MIGRATIONS[0].sql
}
