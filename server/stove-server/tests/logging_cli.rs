use std::process::{Command, Output};

use serde_json::Value;
use tempfile::TempDir;

fn stove() -> Command {
  let mut command = Command::new(env!("CARGO_BIN_EXE_stove"));
  for (name, _) in std::env::vars_os() {
    if name.to_string_lossy().starts_with("STOVE_") || name == "RUST_LOG" {
      command.env_remove(name);
    }
  }
  command.args(["--db", ":memory:", "--no-skills-check", "--clear"]);
  command
}

fn json_events(output: &Output) -> Vec<Value> {
  assert!(output.stderr.is_empty(), "{output:?}");
  let stdout = std::str::from_utf8(&output.stdout).unwrap();
  assert!(!stdout.is_empty());
  assert!(!stdout.contains('\u{1b}'));
  stdout
    .lines()
    .map(|line| serde_json::from_str(line).expect("every stdout line must be JSON"))
    .collect()
}

#[test]
fn defaults_to_json_including_fresh_start_messages() {
  let output = stove().arg("--fresh-start").output().unwrap();
  assert!(output.status.success(), "{output:?}");
  let events = json_events(&output);
  assert!(events.iter().any(|event| {
    event["fields"]["message"] == "Starting fresh — database will be recreated."
  }));
  let cleared = events.last().unwrap();
  assert_eq!(cleared["level"], "INFO");
  assert!(cleared["timestamp"].is_string());
  assert_eq!(cleared["target"], "stove");
  assert_eq!(cleared["fields"]["message"], "Cleared all stored runs.");
}

#[test]
fn log_format_respects_file_environment_and_cli_precedence() {
  let directory = TempDir::new().unwrap();
  for (name, contents) in [
    ("stove.toml", "log_format = \"text\""),
    ("stove.json", r#"{"log_format":"text"}"#),
  ] {
    let path = directory.path().join(name);
    std::fs::write(&path, contents).unwrap();
    let mut command = stove();
    command.arg("--config-file").arg(&path);
    let output = command.output().unwrap();
    assert!(output.status.success());
    let stdout = String::from_utf8(output.stdout).unwrap();
    assert!(stdout.contains("Cleared all stored runs."));
    assert!(!stdout.starts_with('{'));

    let output = command.env("STOVE_LOG_FORMAT", "json").output().unwrap();
    assert!(output.status.success());
    json_events(&output);

    let output = command.args(["--log-format", "text"]).output().unwrap();
    assert!(output.status.success());
    let stdout = String::from_utf8(output.stdout).unwrap();
    assert!(stdout.contains("Cleared all stored runs."));
    assert!(!stdout.starts_with('{'));
  }
}

#[test]
fn rejects_unknown_log_formats() {
  let output = stove().args(["--log-format", "xml"]).output().unwrap();
  assert!(!output.status.success());
  assert!(String::from_utf8_lossy(&output.stderr).contains("invalid value 'xml'"));
}

#[test]
fn rust_log_filters_both_formats() {
  for format in ["json", "text"] {
    let output = stove()
      .args(["--log-format", format])
      .env("RUST_LOG", "error")
      .output()
      .unwrap();
    assert!(output.status.success());
    assert!(output.stdout.is_empty(), "{output:?}");
  }
}

#[test]
fn runtime_failures_are_json_and_exit_unsuccessfully() {
  let output = stove()
    .args([
      "--fresh-start",
      "--database-url",
      "postgresql://unused/stove",
    ])
    .output()
    .unwrap();
  assert!(!output.status.success());
  let events = json_events(&output);
  let failure = events.last().unwrap();
  assert_eq!(failure["level"], "ERROR");
  assert_eq!(failure["fields"]["message"], "Stove server failed");
  assert!(
    failure["fields"]["error"]
      .as_str()
      .unwrap()
      .contains("--fresh-start")
  );
}
