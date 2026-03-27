use std::path::Path;

use clap::Parser;

/// CLI configuration parsed from command-line arguments.
#[derive(Parser, Debug)]
#[command(
  name = "stove",
  about = "Stove Portal \u{2014} local e2e test observability",
  version = env!("STOVE_VERSION")
)]
pub struct Config {
  /// HTTP port for the web UI and REST API
  #[arg(long, default_value_t = 4040)]
  pub port: u16,

  /// gRPC port for receiving events from Stove test process
  #[arg(long, default_value_t = 4041)]
  pub grpc_port: u16,

  /// Path to `SQLite` database file
  #[arg(long, default_value_t = default_db_path())]
  pub db: String,

  /// Clear all stored runs and exit
  #[arg(long)]
  pub clear: bool,

  /// Drop and recreate the database from scratch (backs up existing file first)
  #[arg(long)]
  pub fresh_start: bool,
}

/// If `--fresh-start` is set, backs up the existing database file and deletes the original.
/// Returns `Ok(Some(backup_path))` if a backup was created, `Ok(None)` if no file existed.
/// Skips in-memory databases.
pub fn handle_fresh_start(db_path: &str) -> std::io::Result<Option<String>> {
  if db_path == ":memory:" {
    return Ok(None);
  }

  let path = Path::new(db_path);
  if !path.exists() {
    return Ok(None);
  }

  let timestamp = chrono::Local::now().format("%Y%m%d-%H%M%S");
  let backup_path = format!("{db_path}.backup-{timestamp}");
  std::fs::copy(path, &backup_path)?;
  std::fs::remove_file(path)?;
  Ok(Some(backup_path))
}

/// Returns the default database path in the user's home directory.
fn default_db_path() -> String {
  dirs_fallback()
    .join(".stove-portal.db")
    .to_string_lossy()
    .to_string()
}

/// Best-effort home directory lookup without pulling in the `dirs` crate.
fn dirs_fallback() -> std::path::PathBuf {
  std::env::var("HOME")
    .or_else(|_| std::env::var("USERPROFILE"))
    .map_or_else(
      |_| std::env::current_dir().unwrap_or_else(|_| ".".into()),
      std::path::PathBuf::from,
    )
}

#[cfg(test)]
mod tests {
  use super::*;
  use std::fs;
  use tempfile::TempDir;

  #[test]
  fn fresh_start_backs_up_and_deletes_existing_db() {
    let dir = TempDir::new().unwrap();
    let db_path = dir.path().join("test.db");
    fs::write(&db_path, b"some data").unwrap();

    let result = handle_fresh_start(db_path.to_str().unwrap()).unwrap();

    assert!(result.is_some(), "should return backup path");
    let backup_path = result.unwrap();
    assert!(Path::new(&backup_path).exists(), "backup file should exist");
    assert!(!db_path.exists(), "original file should be deleted");
    assert_eq!(fs::read(&backup_path).unwrap(), b"some data");
  }

  #[test]
  fn fresh_start_returns_none_when_file_does_not_exist() {
    let dir = TempDir::new().unwrap();
    let db_path = dir.path().join("nonexistent.db");

    let result = handle_fresh_start(db_path.to_str().unwrap()).unwrap();

    assert!(result.is_none());
  }

  #[test]
  fn fresh_start_skips_in_memory_database() {
    let result = handle_fresh_start(":memory:").unwrap();

    assert!(result.is_none());
  }

  #[test]
  fn cli_parses_default_values() {
    let config = Config::try_parse_from(["stove"]).unwrap();

    assert_eq!(config.port, 4040);
    assert_eq!(config.grpc_port, 4041);
    assert!(!config.clear);
    assert!(!config.fresh_start);
  }

  #[test]
  fn cli_parses_custom_ports() {
    let config =
      Config::try_parse_from(["stove", "--port", "8080", "--grpc-port", "9090"]).unwrap();

    assert_eq!(config.port, 8080);
    assert_eq!(config.grpc_port, 9090);
  }

  #[test]
  fn cli_parses_clear_flag() {
    let config = Config::try_parse_from(["stove", "--clear"]).unwrap();

    assert!(config.clear);
  }

  #[test]
  fn cli_parses_fresh_start_flag() {
    let config = Config::try_parse_from(["stove", "--fresh-start"]).unwrap();

    assert!(config.fresh_start);
  }

  #[test]
  fn cli_parses_custom_db_path() {
    let config = Config::try_parse_from(["stove", "--db", "/tmp/my.db"]).unwrap();

    assert_eq!(config.db, "/tmp/my.db");
  }
}
