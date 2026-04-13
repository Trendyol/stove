use crate::error::Result;
use rusqlite::{Connection, OpenFlags};
use std::sync::atomic::{AtomicUsize, Ordering};
use tracing::info;

/// Versioned SQL migrations, embedded at compile time.
/// Add new migrations by creating `V{N}__description.sql` in `src/storage/migrations/`.
/// Once deployed, never modify an existing migration — append a new one instead.
const MIGRATIONS: &[(&str, &str)] = &[
  (
    "V1__initial_schema",
    include_str!("migrations/V1__initial_schema.sql"),
  ),
  (
    "V2__run_stove_version",
    include_str!("migrations/V2__run_stove_version.sql"),
  ),
  (
    "V3__test_path",
    include_str!("migrations/V3__test_path.sql"),
  ),
];

/// `SQLite` database wrapper with WAL mode and versioned schema migrations.
pub struct Database {
  path: String,
  use_uri: bool,
  conn: Connection,
}

impl Database {
  /// Open (or create) the database at the given path.
  ///
  /// Uses WAL mode for concurrent reads and runs versioned migrations on startup.
  pub fn open(path: &str) -> Result<Self> {
    let (path, use_uri) = normalize_db_path(path);
    let conn = open_connection(&path, use_uri)?;
    apply_pragmas(&conn, &path)?;

    run_migrations(&conn)?;

    Ok(Self {
      path,
      use_uri,
      conn,
    })
  }

  /// Returns a reference to the underlying connection.
  pub fn conn(&self) -> &Connection {
    &self.conn
  }

  /// Returns a mutable reference to the underlying connection.
  pub fn conn_mut(&mut self) -> &mut Connection {
    &mut self.conn
  }

  /// Open another connection to the same database.
  ///
  /// The CLI uses this to isolate read traffic from the write path so the UI can
  /// keep polling while gRPC ingestion is busy.
  pub fn open_peer(&self) -> Result<Self> {
    let conn = open_connection(&self.path, self.use_uri)?;
    apply_pragmas(&conn, &self.path)?;
    Ok(Self {
      path: self.path.clone(),
      use_uri: self.use_uri,
      conn,
    })
  }
}

/// Run versioned migrations that haven't been applied yet.
///
/// Tracks applied migrations in a `schema_migrations` table. Each migration is
/// applied inside a transaction and recorded with its name and version number.
fn run_migrations(conn: &Connection) -> Result<()> {
  conn.execute_batch(
    "CREATE TABLE IF NOT EXISTS schema_migrations (
            version INTEGER PRIMARY KEY,
            name TEXT NOT NULL,
            applied_at TEXT NOT NULL DEFAULT (datetime('now'))
        );",
  )?;

  let current_version: i64 = conn.query_row(
    "SELECT COALESCE(MAX(version), 0) FROM schema_migrations",
    [],
    |row| row.get(0),
  )?;

  for (i, (name, sql)) in MIGRATIONS.iter().enumerate() {
    #[allow(clippy::cast_possible_wrap)]
    let version = (i + 1) as i64;
    if version <= current_version {
      continue;
    }

    let tx = conn.unchecked_transaction()?;
    tx.execute_batch(sql)?;
    tx.execute(
      "INSERT INTO schema_migrations (version, name) VALUES (?1, ?2)",
      rusqlite::params![version, name],
    )?;
    tx.commit()?;

    info!(version, name, "Applied migration");
  }

  Ok(())
}

fn normalize_db_path(path: &str) -> (String, bool) {
  if path == ":memory:" {
    let id = IN_MEMORY_DB_COUNTER.fetch_add(1, Ordering::Relaxed);
    (
      format!("file:stove-test-{id}?mode=memory&cache=shared"),
      true,
    )
  } else {
    (path.to_string(), false)
  }
}

fn open_connection(path: &str, use_uri: bool) -> Result<Connection> {
  let conn = if use_uri {
    Connection::open_with_flags(
      path,
      OpenFlags::SQLITE_OPEN_READ_WRITE
        | OpenFlags::SQLITE_OPEN_CREATE
        | OpenFlags::SQLITE_OPEN_URI,
    )?
  } else {
    Connection::open(path)?
  };
  Ok(conn)
}

fn apply_pragmas(conn: &Connection, path: &str) -> Result<()> {
  if path.starts_with("file:stove-test-") {
    conn.execute_batch("PRAGMA foreign_keys=ON;")?;
  } else {
    conn.execute_batch("PRAGMA journal_mode=WAL; PRAGMA foreign_keys=ON;")?;
  }
  Ok(())
}

static IN_MEMORY_DB_COUNTER: AtomicUsize = AtomicUsize::new(0);

#[cfg(test)]
mod tests {
  use super::*;
  use tempfile::TempDir;

  #[test]
  fn open_in_memory_succeeds_and_creates_tables() {
    let db = Database::open(":memory:").expect("should open in-memory database");

    let tables: Vec<String> = db
      .conn()
      .prepare("SELECT name FROM sqlite_master WHERE type='table' ORDER BY name")
      .unwrap()
      .query_map([], |row| row.get(0))
      .unwrap()
      .filter_map(|r| r.ok())
      .collect();

    assert!(tables.contains(&"runs".to_string()));
    assert!(tables.contains(&"tests".to_string()));
    assert!(tables.contains(&"entries".to_string()));
    assert!(tables.contains(&"spans".to_string()));
    assert!(tables.contains(&"snapshots".to_string()));
  }

  #[test]
  fn migrations_are_idempotent() {
    let db = Database::open(":memory:").expect("first open");

    // Running migrations again should be a no-op
    run_migrations(db.conn()).expect("re-run should succeed");

    let version: i64 = db
      .conn()
      .query_row("SELECT MAX(version) FROM schema_migrations", [], |row| {
        row.get(0)
      })
      .unwrap();
    assert_eq!(version, MIGRATIONS.len() as i64);
  }

  #[test]
  fn open_upgrades_v1_database_with_run_stove_version_column() {
    let dir = TempDir::new().unwrap();
    let path = dir.path().join("stove-v1.db");
    let conn = Connection::open(&path).unwrap();

    conn.execute_batch(MIGRATIONS[0].1).unwrap();
    conn
      .execute_batch(
        "CREATE TABLE IF NOT EXISTS schema_migrations (
          version INTEGER PRIMARY KEY,
          name TEXT NOT NULL,
          applied_at TEXT NOT NULL DEFAULT (datetime('now'))
      );",
      )
      .unwrap();
    conn
      .execute(
        "INSERT INTO schema_migrations (version, name) VALUES (?1, ?2)",
        rusqlite::params![1_i64, "V1__initial_schema"],
      )
      .unwrap();
    drop(conn);

    let db = Database::open(path.to_str().unwrap()).unwrap();
    let stove_version_columns: i64 = db
      .conn()
      .query_row(
        "SELECT COUNT(*) FROM pragma_table_info('runs') WHERE name = 'stove_version'",
        [],
        |row| row.get(0),
      )
      .unwrap();
    let schema_version: i64 = db
      .conn()
      .query_row("SELECT MAX(version) FROM schema_migrations", [], |row| {
        row.get(0)
      })
      .unwrap();

    assert_eq!(stove_version_columns, 1);
    assert_eq!(schema_version, MIGRATIONS.len() as i64);
  }
}
