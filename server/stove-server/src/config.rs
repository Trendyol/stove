use std::ffi::OsString;
use std::path::{Path, PathBuf};

use anyhow::{Context, Result, bail};
use clap::{Parser, Subcommand};
use serde::Deserialize;

const DEFAULT_HTTP_PORT: u16 = 4040;
const DEFAULT_GRPC_PORT: u16 = 4041;
const DEFAULT_RETENTION_RUNS_PER_APP: usize = 1;

/// Resolved server configuration. Command-line arguments and environment variables
/// override values loaded from the optional TOML or JSON configuration file.
#[allow(clippy::struct_excessive_bools)] // CLI flags are naturally bool-heavy
pub struct Config {
  pub port: u16,
  pub grpc_port: u16,
  pub db: String,
  pub database_url: Option<String>,
  pub retention_runs_per_app: usize,
  pub ingestion_capacity: usize,
  pub read_capacity: usize,
  pub replay_capacity: usize,
  pub stream_capacity: usize,
  pub postgres_replay_readers: usize,
  pub postgres_readers: usize,
  pub postgres_writers: usize,
  pub clear: bool,
  pub fresh_start: bool,
  pub update_skills: bool,
  pub no_skills_check: bool,
  pub command: Option<StoveCommand>,
}

/// Command-line and environment overrides. Optional value types preserve whether
/// an override was supplied, which is required for deterministic file merging.
#[derive(Parser)]
#[command(
  name = "stove",
  about = "Stove Server \u{2014} local e2e test observability",
  version = env!("STOVE_VERSION")
)]
#[allow(clippy::struct_excessive_bools)] // CLI flags are naturally bool-heavy
struct CliConfig {
  /// Path to a TOML server configuration file
  #[arg(long, env = "STOVE_CONFIG_FILE", value_name = "PATH")]
  config_file: Option<PathBuf>,

  /// HTTP port for the web UI and REST API
  #[arg(long, env = "STOVE_PORT")]
  port: Option<u16>,

  /// gRPC port for receiving events from Stove test process
  #[arg(long, env = "STOVE_GRPC_PORT")]
  grpc_port: Option<u16>,

  /// Path to `SQLite` database file
  #[arg(long, env = "STOVE_DB")]
  db: Option<PathBuf>,

  /// `PostgreSQL` connection URL. When set, it replaces the local `SQLite` database.
  #[arg(long, env = "STOVE_DATABASE_URL", conflicts_with = "database_url_file")]
  database_url: Option<String>,

  /// Read the `PostgreSQL` connection URL from this file
  #[arg(
    long,
    env = "STOVE_DATABASE_URL_FILE",
    value_name = "PATH",
    conflicts_with = "database_url"
  )]
  database_url_file: Option<PathBuf>,

  /// Number of completed runs retained per application. Zero disables automatic pruning.
  #[arg(long, env = "STOVE_RETENTION_RUNS_PER_APP")]
  retention_runs_per_app: Option<usize>,

  /// Maximum queued and running ingestion operations.
  #[arg(long, env = "STOVE_INGESTION_CAPACITY")]
  ingestion_capacity: Option<usize>,

  /// Maximum queued and running interactive read operations.
  #[arg(long, env = "STOVE_READ_CAPACITY")]
  read_capacity: Option<usize>,

  /// Maximum queued and running durable replay operations.
  #[arg(long, env = "STOVE_REPLAY_CAPACITY")]
  replay_capacity: Option<usize>,

  /// Maximum simultaneous SSE subscribers per pod.
  #[arg(long, env = "STOVE_STREAM_CAPACITY")]
  stream_capacity: Option<usize>,

  /// PostgreSQL connection pool size (1..64).
  #[arg(long, env = "STOVE_POSTGRES_WRITERS")]
  postgres_writers: Option<usize>,

  /// PostgreSQL connection pool size (1..64).
  #[arg(long, env = "STOVE_POSTGRES_READERS")]
  postgres_readers: Option<usize>,

  /// PostgreSQL connection pool size (1..64).
  #[arg(long, env = "STOVE_POSTGRES_REPLAY_READERS")]
  postgres_replay_readers: Option<usize>,

  /// Clear all stored runs and exit
  #[arg(long)]
  clear: bool,

  /// Drop and recreate the database from scratch (backs up existing file first)
  #[arg(long)]
  fresh_start: bool,

  /// Fetch and apply Stove agent skills from GitHub on startup without prompting.
  /// Useful for automation inside repositories.
  #[arg(long)]
  update_skills: bool,

  /// Skip the startup Stove agent skills check entirely.
  #[arg(long)]
  no_skills_check: bool,

  /// Optional subcommand. When omitted, the server runs the dashboard.
  #[command(subcommand)]
  command: Option<StoveCommand>,
}

#[derive(Default, Deserialize)]
#[serde(deny_unknown_fields)]
struct FileConfig {
  read_capacity: Option<usize>,
  replay_capacity: Option<usize>,
  stream_capacity: Option<usize>,
  postgres_replay_readers: Option<usize>,
  postgres_readers: Option<usize>,
  postgres_writers: Option<usize>,
  ingestion_capacity: Option<usize>,
  port: Option<u16>,
  grpc_port: Option<u16>,
  db: Option<PathBuf>,
  database_url: Option<String>,
  database_url_file: Option<PathBuf>,
  retention_runs_per_app: Option<usize>,
}

/// Top-level subcommands for the Stove Server.
#[derive(Subcommand, Debug)]
pub enum StoveCommand {
  /// Manage Stove agent skills under the current project.
  Skills {
    #[command(subcommand)]
    command: SkillsCommand,
  },
}

/// `stove skills <...>` subcommands.
#[derive(Subcommand, Debug)]
pub enum SkillsCommand {
  /// Install or update Stove agent skills from GitHub.
  Install {
    /// Skip git repository detection and overwrite without prompting.
    /// Installs into the resolved skill target relative to the current directory.
    #[arg(long)]
    force: bool,
  },
}

impl Config {
  pub fn parse() -> Result<Self> {
    Self::resolve(CliConfig::parse())
  }

  pub fn try_parse_from<I, T>(arguments: I) -> Result<Self>
  where
    I: IntoIterator<Item = T>,
    T: Into<OsString> + Clone,
  {
    Self::resolve(CliConfig::try_parse_from(arguments)?)
  }

  fn resolve(cli: CliConfig) -> Result<Self> {
    let file = cli
      .config_file
      .as_deref()
      .map(load_config_file)
      .transpose()?;
    let file_base = cli.config_file.as_deref().and_then(Path::parent);

    let database_url = resolve_database_url(&cli, file.as_ref(), file_base)?;
    let db = cli.db.map_or_else(
      || {
        file
          .as_ref()
          .and_then(|config| config.db.clone())
          .map_or_else(default_db_path, |path| resolve_path(&path, file_base))
      },
      |path| path.to_string_lossy().into_owned(),
    );

    let read_capacity = cli
      .read_capacity
      .or_else(|| file.as_ref().and_then(|config| config.read_capacity))
      .unwrap_or(64);
    let replay_capacity = cli
      .replay_capacity
      .or_else(|| file.as_ref().and_then(|config| config.replay_capacity))
      .unwrap_or(16);
    let stream_capacity = cli
      .stream_capacity
      .or_else(|| file.as_ref().and_then(|config| config.stream_capacity))
      .unwrap_or(64);
    let ingestion_capacity = cli
      .ingestion_capacity
      .or_else(|| file.as_ref().and_then(|config| config.ingestion_capacity))
      .unwrap_or(64);

    for (name, capacity) in [
      ("read_capacity", read_capacity),
      ("replay_capacity", replay_capacity),
      ("stream_capacity", stream_capacity),
      ("ingestion_capacity", ingestion_capacity),
    ] {
      if !(1..=65_536).contains(&capacity) {
        bail!("{name} must be between 1 and 65536");
      }
    }

    let postgres_writers = cli
      .postgres_writers
      .or_else(|| file.as_ref().and_then(|config| config.postgres_writers))
      .unwrap_or(4);

    let postgres_readers = cli
      .postgres_readers
      .or_else(|| file.as_ref().and_then(|config| config.postgres_readers))
      .unwrap_or(4);

    let postgres_replay_readers = cli
      .postgres_replay_readers
      .or_else(|| {
        file
          .as_ref()
          .and_then(|config| config.postgres_replay_readers)
      })
      .unwrap_or(2);

    for (name, capacity) in [
      ("postgres_writers", postgres_writers),
      ("postgres_readers", postgres_readers),
      ("postgres_replay_readers", postgres_replay_readers),
    ] {
      if !(1..=64).contains(&capacity) {
        bail!("{name} must be between 1 and 64");
      }
    }

    Ok(Self {
      postgres_replay_readers,
      postgres_readers,
      postgres_writers,
      ingestion_capacity,
      read_capacity,
      replay_capacity,
      stream_capacity,
      port: cli
        .port
        .or_else(|| file.as_ref().and_then(|config| config.port))
        .unwrap_or(DEFAULT_HTTP_PORT),
      grpc_port: cli
        .grpc_port
        .or_else(|| file.as_ref().and_then(|config| config.grpc_port))
        .unwrap_or(DEFAULT_GRPC_PORT),
      db,
      database_url,
      retention_runs_per_app: cli
        .retention_runs_per_app
        .or_else(|| {
          file
            .as_ref()
            .and_then(|config| config.retention_runs_per_app)
        })
        .unwrap_or(DEFAULT_RETENTION_RUNS_PER_APP),
      clear: cli.clear,
      fresh_start: cli.fresh_start,
      update_skills: cli.update_skills,
      no_skills_check: cli.no_skills_check,
      command: cli.command,
    })
  }
}

fn load_config_file(path: &Path) -> Result<FileConfig> {
  let contents = std::fs::read_to_string(path)
    .with_context(|| format!("read Stove configuration file {}", path.display()))?;
  let extension = path
    .extension()
    .and_then(|extension| extension.to_str())
    .map(str::to_ascii_lowercase);
  match extension.as_deref() {
    Some("json") => parse_config(&contents, config_rs::FileFormat::Json)
      .with_context(|| format!("parse JSON Stove configuration file {}", path.display())),
    Some("toml") => parse_config(&contents, config_rs::FileFormat::Toml)
      .with_context(|| format!("parse TOML Stove configuration file {}", path.display())),
    _ => parse_extensionless_config(&contents, path),
  }
}

fn parse_extensionless_config(contents: &str, path: &Path) -> Result<FileConfig> {
  match parse_config(contents, config_rs::FileFormat::Toml) {
    Ok(config) => Ok(config),
    Err(toml_error) => parse_config(contents, config_rs::FileFormat::Json).map_err(|json_error| {
      anyhow::anyhow!(
        "parse Stove configuration file {} as TOML or JSON; TOML: {}; JSON: {}",
        path.display(),
        toml_error,
        json_error
      )
    }),
  }
}

fn parse_config(contents: &str, format: config_rs::FileFormat) -> Result<FileConfig> {
  config_rs::Config::builder()
    .add_source(config_rs::File::from_str(contents, format))
    .build()?
    .try_deserialize()
    .map_err(Into::into)
}

fn resolve_database_url(
  cli: &CliConfig,
  file: Option<&FileConfig>,
  file_base: Option<&Path>,
) -> Result<Option<String>> {
  if cli.database_url.is_some() || cli.database_url_file.is_some() {
    return database_url_from_sources(
      cli.database_url.as_deref(),
      cli.database_url_file.as_deref(),
      None,
    );
  }

  let Some(file) = file else {
    return Ok(None);
  };
  database_url_from_sources(
    file.database_url.as_deref(),
    file.database_url_file.as_deref(),
    file_base,
  )
}

fn database_url_from_sources(
  inline: Option<&str>,
  file_path: Option<&Path>,
  relative_to: Option<&Path>,
) -> Result<Option<String>> {
  match (inline, file_path) {
    (Some(_), Some(_)) => bail!(
      "configure only one of database_url and database_url_file (or their CLI/environment equivalents)"
    ),
    (Some(url), None) => validate_database_url(url).map(Some),
    (None, Some(path)) => {
      let path = rebase_path(path, relative_to);
      let contents = std::fs::read_to_string(&path)
        .with_context(|| format!("read PostgreSQL connection URL from {}", path.display()))?;
      validate_database_url(contents.trim()).map(Some)
    }
    (None, None) => Ok(None),
  }
}

fn validate_database_url(url: &str) -> Result<String> {
  if url.is_empty() {
    bail!("PostgreSQL connection URL must not be empty");
  }
  Ok(url.to_string())
}

fn resolve_path(path: &Path, relative_to: Option<&Path>) -> String {
  rebase_path(path, relative_to)
    .to_string_lossy()
    .into_owned()
}

fn rebase_path(path: &Path, relative_to: Option<&Path>) -> PathBuf {
  if path.is_absolute() {
    path.to_path_buf()
  } else if let Some(base) = relative_to {
    base.join(path)
  } else {
    path.to_path_buf()
  }
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
    .join(".stove-dashboard.db")
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
    assert!(config.database_url.is_none());
    assert_eq!(config.retention_runs_per_app, 1);
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

  #[test]
  fn cli_parses_postgres_database_url() {
    let config = Config::try_parse_from([
      "stove",
      "--database-url",
      "postgresql://stove:secret@db.example/stove",
    ])
    .unwrap();

    assert_eq!(
      config.database_url.as_deref(),
      Some("postgresql://stove:secret@db.example/stove")
    );
  }

  #[test]
  fn cli_reads_postgres_database_url_from_a_secret_file() {
    let dir = TempDir::new().unwrap();
    let secret_path = dir.path().join("database-url");
    fs::write(&secret_path, "postgresql://stove:secret@db.example/stove\n").unwrap();

    let config = Config::try_parse_from([
      "stove",
      "--database-url-file",
      secret_path.to_str().unwrap(),
    ])
    .unwrap();

    assert_eq!(
      config.database_url.as_deref(),
      Some("postgresql://stove:secret@db.example/stove")
    );
  }

  #[test]
  fn toml_config_resolves_relative_database_and_secret_paths() {
    let dir = TempDir::new().unwrap();
    let secret_path = dir.path().join("database-url");
    let config_path = dir.path().join("stove.toml");
    fs::write(&secret_path, "postgresql://stove:secret@postgres/stove\n").unwrap();
    fs::write(
      &config_path,
      r#"
port = 8080
grpc_port = 8081
db = "data/stove.db"
database_url_file = "database-url"
retention_runs_per_app = 50
"#,
    )
    .unwrap();

    let config =
      Config::try_parse_from(["stove", "--config-file", config_path.to_str().unwrap()]).unwrap();

    assert_eq!(config.port, 8080);
    assert_eq!(config.grpc_port, 8081);
    assert_eq!(
      config.db,
      dir.path().join("data/stove.db").to_string_lossy()
    );
    assert_eq!(
      config.database_url.as_deref(),
      Some("postgresql://stove:secret@postgres/stove")
    );
    assert_eq!(config.retention_runs_per_app, 50);
  }

  #[test]
  fn json_config_uses_the_same_schema() {
    let dir = TempDir::new().unwrap();
    let config_path = dir.path().join("stove.json");
    fs::write(
      &config_path,
      r#"{
  "port": 7070,
  "grpc_port": 7071,
  "database_url": "postgresql://stove:secret@postgres/stove",
  "retention_runs_per_app": 25
}"#,
    )
    .unwrap();

    let config =
      Config::try_parse_from(["stove", "--config-file", config_path.to_str().unwrap()]).unwrap();

    assert_eq!(config.port, 7070);
    assert_eq!(config.grpc_port, 7071);
    assert_eq!(
      config.database_url.as_deref(),
      Some("postgresql://stove:secret@postgres/stove")
    );
    assert_eq!(config.retention_runs_per_app, 25);
  }

  #[test]
  fn cli_values_override_config_file_values_and_sources() {
    let dir = TempDir::new().unwrap();
    let config_path = dir.path().join("stove.toml");
    fs::write(
      &config_path,
      "port = 5050\ndatabase_url_file = \"missing-secret\"\nretention_runs_per_app = 10\n",
    )
    .unwrap();

    let config = Config::try_parse_from([
      "stove",
      "--config-file",
      config_path.to_str().unwrap(),
      "--port",
      "6060",
      "--database-url",
      "postgresql://override/stove",
      "--retention-runs-per-app",
      "2",
    ])
    .unwrap();

    assert_eq!(config.port, 6060);
    assert_eq!(
      config.database_url.as_deref(),
      Some("postgresql://override/stove")
    );
    assert_eq!(config.retention_runs_per_app, 2);
  }

  #[test]
  fn config_rejects_multiple_database_url_sources() {
    let dir = TempDir::new().unwrap();
    let config_path = dir.path().join("stove.json");
    fs::write(
      &config_path,
      r#"{"database_url":"postgresql://inline/stove","database_url_file":"database-url"}"#,
    )
    .unwrap();

    let error = Config::try_parse_from(["stove", "--config-file", config_path.to_str().unwrap()])
      .err()
      .expect("conflicting database URL sources should fail");

    assert!(error.to_string().contains("configure only one"));
  }

  #[test]
  fn ingestion_capacity_validates_bounds_and_cli_overrides_file() {
    for capacity in ["0", "65537"] {
      assert!(Config::try_parse_from(["stove", "--ingestion-capacity", capacity]).is_err());
    }
    let directory = tempfile::tempdir().unwrap();
    let path = directory.path().join("stove.toml");
    fs::write(&path, "ingestion_capacity = 8").unwrap();
    let path = path.to_str().unwrap();
    assert_eq!(
      Config::try_parse_from(["stove", "--config-file", path])
        .unwrap()
        .ingestion_capacity,
      8
    );
    assert_eq!(
      Config::try_parse_from(["stove", "--config-file", path, "--ingestion-capacity", "16"])
        .unwrap()
        .ingestion_capacity,
      16
    );
  }

  #[test]
  fn read_replay_and_stream_capacity_follow_configuration_precedence() {
    for flag in ["--read-capacity", "--replay-capacity", "--stream-capacity"] {
      for capacity in ["0", "65537"] {
        assert!(Config::try_parse_from(["stove", flag, capacity]).is_err());
      }
    }
    let directory = tempfile::tempdir().unwrap();
    let path = directory.path().join("stove.toml");
    fs::write(
      &path,
      "read_capacity = 3\nreplay_capacity = 4\nstream_capacity = 5",
    )
    .unwrap();
    let file = Config::try_parse_from(["stove", "--config-file", path.to_str().unwrap()]).unwrap();
    assert_eq!(
      (
        file.read_capacity,
        file.replay_capacity,
        file.stream_capacity
      ),
      (3, 4, 5)
    );
    let cli = Config::try_parse_from([
      "stove",
      "--config-file",
      path.to_str().unwrap(),
      "--read-capacity",
      "6",
      "--replay-capacity",
      "7",
      "--stream-capacity",
      "8",
    ])
    .unwrap();
    assert_eq!(
      (cli.read_capacity, cli.replay_capacity, cli.stream_capacity),
      (6, 7, 8)
    );
  }

  #[test]
  fn cli_parses_custom_run_retention() {
    let config = Config::try_parse_from(["stove", "--retention-runs-per-app", "25"]).unwrap();

    assert_eq!(config.retention_runs_per_app, 25);
  }

  #[test]
  fn cli_defaults_skills_flags_off() {
    let config = Config::try_parse_from(["stove"]).unwrap();
    assert!(!config.update_skills);
    assert!(!config.no_skills_check);
    assert!(config.command.is_none());
  }

  #[test]
  fn cli_parses_update_skills_flag() {
    let config = Config::try_parse_from(["stove", "--update-skills"]).unwrap();
    assert!(config.update_skills);
  }

  #[test]
  fn cli_parses_no_skills_check_flag() {
    let config = Config::try_parse_from(["stove", "--no-skills-check"]).unwrap();
    assert!(config.no_skills_check);
  }

  #[test]
  fn cli_parses_skills_install_subcommand() {
    let config = Config::try_parse_from(["stove", "skills", "install"]).unwrap();
    let Some(StoveCommand::Skills { command }) = config.command else {
      panic!("expected skills subcommand");
    };
    let SkillsCommand::Install { force } = command;
    assert!(!force);
  }

  #[test]
  fn cli_parses_skills_install_force() {
    let config = Config::try_parse_from(["stove", "skills", "install", "--force"]).unwrap();
    let Some(StoveCommand::Skills { command }) = config.command else {
      panic!("expected skills subcommand");
    };
    let SkillsCommand::Install { force } = command;
    assert!(force);
  }
}
