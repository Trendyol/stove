use std::sync::atomic::{AtomicUsize, Ordering};

use rusqlite::{Connection, OpenFlags};

use crate::error::Result;
use crate::storage::migrations::sqlite::run_migrations;

/// `SQLite` connection wrapper with WAL mode and versioned schema migrations.
pub(in crate::storage::repository) struct SqliteDatabase {
  path: String,
  use_uri: bool,
  connection: Connection,
}

impl SqliteDatabase {
  /// Open or create a database and apply all pending migrations.
  pub(super) fn open(path: &str) -> Result<Self> {
    let (path, use_uri) = normalize_path(path);
    let connection = open_connection(&path, use_uri)?;
    apply_pragmas(&connection, &path)?;
    run_migrations(&connection)?;

    Ok(Self {
      path,
      use_uri,
      connection,
    })
  }

  pub(in crate::storage::repository) fn conn(&self) -> &Connection {
    &self.connection
  }

  pub(in crate::storage::repository) fn conn_mut(&mut self) -> &mut Connection {
    &mut self.connection
  }

  /// Open another connection to the same database for independent reads.
  pub(super) fn open_peer(&self) -> Result<Self> {
    let connection = open_connection(&self.path, self.use_uri)?;
    apply_pragmas(&connection, &self.path)?;
    Ok(Self {
      path: self.path.clone(),
      use_uri: self.use_uri,
      connection,
    })
  }
}

fn normalize_path(path: &str) -> (String, bool) {
  if path == ":memory:" {
    let id = IN_MEMORY_DATABASE_COUNTER.fetch_add(1, Ordering::Relaxed);
    (
      format!("file:stove-test-{id}?mode=memory&cache=shared"),
      true,
    )
  } else {
    (path.to_string(), false)
  }
}

fn open_connection(path: &str, use_uri: bool) -> Result<Connection> {
  if use_uri {
    return Ok(Connection::open_with_flags(
      path,
      OpenFlags::SQLITE_OPEN_READ_WRITE
        | OpenFlags::SQLITE_OPEN_CREATE
        | OpenFlags::SQLITE_OPEN_URI,
    )?);
  }
  Ok(Connection::open(path)?)
}

fn apply_pragmas(connection: &Connection, path: &str) -> Result<()> {
  if path.starts_with("file:stove-test-") {
    connection.execute_batch("PRAGMA foreign_keys=ON;")?;
  } else {
    connection.execute_batch("PRAGMA journal_mode=WAL; PRAGMA foreign_keys=ON;")?;
  }
  Ok(())
}

static IN_MEMORY_DATABASE_COUNTER: AtomicUsize = AtomicUsize::new(0);

#[cfg(test)]
mod tests {
  use super::*;
  use crate::storage::migrations::sqlite::{initial_schema, migration_count};
  use crate::storage::repository::Repository;
  use tempfile::TempDir;

  #[test]
  fn open_in_memory_succeeds_and_creates_tables() {
    let database = SqliteDatabase::open(":memory:").expect("should open in-memory database");

    let tables: Vec<String> = database
      .conn()
      .prepare("SELECT name FROM sqlite_master WHERE type='table' ORDER BY name")
      .unwrap()
      .query_map([], |row| row.get(0))
      .unwrap()
      .filter_map(std::result::Result::ok)
      .collect();

    assert!(tables.contains(&"runs".to_string()));
    assert!(tables.contains(&"tests".to_string()));
    assert!(tables.contains(&"entries".to_string()));
    assert!(tables.contains(&"spans".to_string()));
    assert!(tables.contains(&"snapshots".to_string()));
  }

  #[test]
  fn migrations_are_idempotent() {
    let database = SqliteDatabase::open(":memory:").expect("first open");

    run_migrations(database.conn()).expect("re-run should succeed");

    let version: i64 = database
      .conn()
      .query_row("SELECT MAX(version) FROM schema_migrations", [], |row| {
        row.get(0)
      })
      .unwrap();
    assert_eq!(version, i64::try_from(migration_count()).unwrap());
  }

  #[test]
  fn open_upgrades_v1_database_and_preserves_legacy_entries() {
    let directory = TempDir::new().unwrap();
    let path = directory.path().join("stove-v1.db");
    let connection = Connection::open(&path).unwrap();

    connection.execute_batch(initial_schema()).unwrap();
    connection
      .execute_batch(
        "CREATE TABLE IF NOT EXISTS schema_migrations (
          version INTEGER PRIMARY KEY,
          name TEXT NOT NULL,
          applied_at TEXT NOT NULL DEFAULT (datetime('now'))
      );",
      )
      .unwrap();
    connection
      .execute(
        "INSERT INTO schema_migrations (version, name) VALUES (?1, ?2)",
        rusqlite::params![1_i64, "V1__initial_schema"],
      )
      .unwrap();
    connection
      .execute_batch(
        "INSERT INTO runs (id, app_name, started_at)
           VALUES ('legacy-run', 'legacy-app', '2024-01-01T00:00:00Z');
         INSERT INTO tests (id, run_id, test_name, spec_name, started_at)
           VALUES ('legacy-test', 'legacy-run', 'legacy test', 'LegacySpec',
                   '2024-01-01T00:00:01Z');
         INSERT INTO entries (
           run_id, test_id, timestamp, system, action, result, input
         ) VALUES (
           'legacy-run', 'legacy-test', '2024-01-01T00:00:02Z',
           'HTTP', 'GET /legacy', 'PASSED', '/legacy'
         );",
      )
      .unwrap();
    drop(connection);

    let database = SqliteDatabase::open(path.to_str().unwrap()).unwrap();
    let stove_version_columns: i64 = database
      .conn()
      .query_row(
        "SELECT COUNT(*) FROM pragma_table_info('runs') WHERE name = 'stove_version'",
        [],
        |row| row.get(0),
      )
      .unwrap();
    let assertion_id_columns: i64 = database
      .conn()
      .query_row(
        "SELECT COUNT(*) FROM pragma_table_info('entries') WHERE name = 'assertion_id'",
        [],
        |row| row.get(0),
      )
      .unwrap();
    let schema_version: i64 = database
      .conn()
      .query_row("SELECT MAX(version) FROM schema_migrations", [], |row| {
        row.get(0)
      })
      .unwrap();
    let stored_assertion_id: String = database
      .conn()
      .query_row(
        "SELECT assertion_id FROM entries WHERE run_id = 'legacy-run'",
        [],
        |row| row.get(0),
      )
      .unwrap();

    assert_eq!(stove_version_columns, 1);
    assert_eq!(assertion_id_columns, 1);
    assert_eq!(schema_version, i64::try_from(migration_count()).unwrap());
    assert_eq!(stored_assertion_id, "");

    drop(database);
    let repository = Repository::connect_sqlite(path.to_str().unwrap(), 1).unwrap();
    let entries = repository.get_entries("legacy-run", "legacy-test").unwrap();
    let raw_entries = repository
      .get_raw_entries("legacy-run", "legacy-test")
      .unwrap();
    let legacy_run = repository.get_run("legacy-run").unwrap().unwrap();
    assert_eq!(entries.len(), 1);
    assert_eq!(raw_entries.len(), 1);
    assert!(legacy_run.metadata.is_empty());
    assert_eq!(entries[0].assertion_id, format!("legacy:{}", entries[0].id));
    assert_eq!(raw_entries[0].assertion_id, entries[0].assertion_id);
  }
}
