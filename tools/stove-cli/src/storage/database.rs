use crate::error::Result;
use rusqlite::Connection;
use tracing::info;

/// Versioned SQL migrations, embedded at compile time.
/// Add new migrations by creating `V{N}__description.sql` in `src/storage/migrations/`.
/// Once deployed, never modify an existing migration — append a new one instead.
const MIGRATIONS: &[(&str, &str)] = &[(
  "V1__initial_schema",
  include_str!("migrations/V1__initial_schema.sql"),
)];

/// `SQLite` database wrapper with WAL mode and versioned schema migrations.
pub struct Database {
  conn: Connection,
}

impl Database {
  /// Open (or create) the database at the given path.
  ///
  /// Uses WAL mode for concurrent reads and runs versioned migrations on startup.
  pub fn open(path: &str) -> Result<Self> {
    let conn = if path == ":memory:" {
      Connection::open_in_memory()?
    } else {
      Connection::open(path)?
    };
    conn.execute_batch("PRAGMA journal_mode=WAL; PRAGMA foreign_keys=ON;")?;

    run_migrations(&conn)?;

    Ok(Self { conn })
  }

  /// Returns a reference to the underlying connection.
  pub fn conn(&self) -> &Connection {
    &self.conn
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

#[cfg(test)]
mod tests {
  use super::*;

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
    assert_eq!(version, 1);
  }
}
