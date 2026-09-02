use std::sync::atomic::{AtomicUsize, Ordering};

use diesel::connection::SimpleConnection;
use diesel::{Connection as DieselConnection, SqliteConnection};
use rusqlite::{Connection as MigrationConnection, OpenFlags};

use crate::error::Result;
use crate::storage::migrations::sqlite::run_migrations;

/// `SQLite` connection wrapper with WAL mode and versioned schema migrations.
pub(in crate::storage::repository) struct SqliteDatabase {
  path: String,
  use_uri: bool,
  connection: SqliteConnection,
  // Keeps a named in-memory database alive after Refinery finishes.
  _migration_keeper: Option<MigrationConnection>,
}

impl SqliteDatabase {
  /// Open or create a database and apply all pending migrations.
  pub(super) fn open(path: &str) -> Result<Self> {
    let (path, use_uri) = normalize_path(path);
    let mut migration_connection = open_migration_connection(&path, use_uri)?;
    apply_migration_pragmas(&migration_connection, use_uri)?;
    run_migrations(&mut migration_connection)?;
    let mut connection = SqliteConnection::establish(&path)?;
    apply_pragmas(&mut connection, use_uri)?;

    Ok(Self {
      path,
      use_uri,
      connection,
      _migration_keeper: use_uri.then_some(migration_connection),
    })
  }

  pub(in crate::storage::repository) fn conn(&mut self) -> &mut SqliteConnection {
    &mut self.connection
  }

  /// Open another connection to the same database for independent reads.
  pub(super) fn open_peer(&self) -> Result<Self> {
    let mut connection = SqliteConnection::establish(&self.path)?;
    apply_pragmas(&mut connection, self.use_uri)?;
    Ok(Self {
      path: self.path.clone(),
      use_uri: self.use_uri,
      connection,
      _migration_keeper: None,
    })
  }

  pub(super) fn open_driver_peer(&self) -> Result<MigrationConnection> {
    let connection = open_migration_connection(&self.path, self.use_uri)?;
    apply_migration_pragmas(&connection, self.use_uri)?;
    Ok(connection)
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

fn open_migration_connection(path: &str, use_uri: bool) -> Result<MigrationConnection> {
  if use_uri {
    return Ok(MigrationConnection::open_with_flags(
      path,
      OpenFlags::SQLITE_OPEN_READ_WRITE
        | OpenFlags::SQLITE_OPEN_CREATE
        | OpenFlags::SQLITE_OPEN_URI,
    )?);
  }
  Ok(MigrationConnection::open(path)?)
}

fn apply_migration_pragmas(connection: &MigrationConnection, in_memory: bool) -> Result<()> {
  connection.execute_batch(pragmas(in_memory))?;
  Ok(())
}

fn apply_pragmas(connection: &mut SqliteConnection, in_memory: bool) -> Result<()> {
  connection.batch_execute(pragmas(in_memory))?;
  Ok(())
}

fn pragmas(in_memory: bool) -> &'static str {
  if in_memory {
    "PRAGMA foreign_keys=ON;"
  } else {
    "PRAGMA journal_mode=WAL; PRAGMA foreign_keys=ON;"
  }
}

static IN_MEMORY_DATABASE_COUNTER: AtomicUsize = AtomicUsize::new(0);

#[cfg(test)]
mod tests {
  use super::*;
  use crate::storage::migrations::sqlite::migration_count;
  use diesel::prelude::*;
  use diesel::sql_types::Text;
  use tempfile::TempDir;

  #[derive(QueryableByName)]
  struct TableName {
    #[diesel(sql_type = Text)]
    name: String,
  }

  #[test]
  fn open_in_memory_succeeds_and_creates_tables() {
    let mut database = SqliteDatabase::open(":memory:").expect("should open in-memory database");

    let tables =
      diesel::sql_query("SELECT name FROM sqlite_master WHERE type='table' ORDER BY name")
        .load::<TableName>(database.conn())
        .unwrap()
        .into_iter()
        .map(|row| row.name)
        .collect::<Vec<_>>();

    assert!(tables.contains(&"runs".to_string()));
    assert!(tables.contains(&"tests".to_string()));
    assert!(tables.contains(&"entries".to_string()));
    assert!(tables.contains(&"spans".to_string()));
    assert!(tables.contains(&"snapshots".to_string()));
  }

  #[test]
  fn migrations_are_idempotent() {
    let directory = TempDir::new().unwrap();
    let path = directory.path().join("stove.db");
    drop(SqliteDatabase::open(path.to_str().unwrap()).expect("first open"));
    drop(SqliteDatabase::open(path.to_str().unwrap()).expect("second open"));
    let connection = MigrationConnection::open(path).unwrap();
    let version: i64 = connection
      .query_row(
        "SELECT MAX(version) FROM refinery_schema_history",
        [],
        |row| row.get(0),
      )
      .unwrap();
    assert_eq!(version, i64::try_from(migration_count()).unwrap());
  }
}
