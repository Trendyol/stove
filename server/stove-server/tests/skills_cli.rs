//! Exercise installation through the real CLI using offline skill fixtures.

use std::fs;
use std::path::Path;
use std::process::{Command, Output};

use tempfile::TempDir;

fn fixture(directory: &Path) {
  fs::create_dir_all(directory.join("references")).unwrap();
  fs::write(
    directory.join("SKILL.md"),
    "---\nname: stove\ndescription: Test Stove setup.\n---\nSee references/setup.md\n",
  )
  .unwrap();
  fs::write(directory.join("references/setup.md"), "nested resource").unwrap();
}

fn install(cwd: &Path, source: &Path, args: &[&str]) -> Output {
  Command::new(env!("CARGO_BIN_EXE_stove"))
    .current_dir(cwd)
    .args(["skills", "install", "--source"])
    .arg(source)
    .args(args)
    .output()
    .unwrap()
}

fn assert_success(output: &Output) {
  assert!(
    output.status.success(),
    "stdout: {}\nstderr: {}",
    String::from_utf8_lossy(&output.stdout),
    String::from_utf8_lossy(&output.stderr)
  );
}

#[test]
fn legacy_install_resolves_git_root_and_force_uses_current_directory() {
  let workspace = TempDir::new().unwrap();
  let source = TempDir::new().unwrap();
  fixture(source.path());
  fs::create_dir(workspace.path().join(".git")).unwrap();
  let nested = workspace.path().join("app");
  fs::create_dir(&nested).unwrap();
  assert_success(&install(&nested, source.path(), &[]));
  assert!(
    workspace
      .path()
      .join(".agents/skills/stove/references/setup.md")
      .is_file()
  );
  assert!(!nested.join(".agents").exists());
  assert_success(&install(&nested, source.path(), &["--force"]));
  assert!(nested.join(".agents/skills/stove/SKILL.md").is_file());
}

#[test]
fn configures_all_agents_preserving_existing_settings_and_nested_resources() {
  for (agent, config_path, original, preserved, skill_path) in [
    (
      "codex",
      ".codex/config.toml",
      "# keep comment\nmodel = 'custom'\n[mcp_servers.other]\nurl = 'https://other.test/mcp'\n",
      "model = 'custom'",
      ".agents/skills/stove",
    ),
    (
      "claude",
      ".mcp.json",
      "{\n  \"mcpServers\": {\"other\": {\"command\": \"my-server\"}}\n}\n",
      "my-server",
      ".claude/skills/stove",
    ),
    (
      "cursor",
      ".cursor/mcp.json",
      "{\n  // keep comment\n  \"mcpServers\": {\"other\": {\"url\": \"https://other.test/mcp\"}}\n}\n",
      "// keep comment",
      ".agents/skills/stove",
    ),
    (
      "opencode",
      "opencode.jsonc",
      "{\n  // keep comment\n  \"model\": \"custom\",\n  \"mcp\": {\"other\": {\"type\": \"remote\", \"url\": \"https://other.test/mcp\"}},\n}\n",
      "// keep comment",
      ".agents/skills/stove",
    ),
  ] {
    let workspace = TempDir::new().unwrap();
    let source = TempDir::new().unwrap();
    fixture(source.path());
    let path = workspace.path().join(config_path);
    fs::create_dir_all(path.parent().unwrap()).unwrap();
    fs::write(&path, original).unwrap();
    let args = [
      "--path",
      ".",
      "--agent",
      agent,
      "--configure-mcp",
      "--mcp-url",
      "https://stove.internal/mcp",
    ];
    assert_success(&install(workspace.path(), source.path(), &args));
    let configured = fs::read_to_string(&path).unwrap();
    assert!(configured.contains(preserved), "{configured}");
    assert!(configured.contains("other"));
    assert!(configured.contains("https://stove.internal/mcp"));
    assert_eq!(
      fs::read_to_string(
        workspace
          .path()
          .join(skill_path)
          .join("references/setup.md")
      )
      .unwrap(),
      "nested resource"
    );
    assert_success(&install(workspace.path(), source.path(), &args));
    assert_eq!(
      fs::read_to_string(&path).unwrap(),
      configured,
      "setup must be idempotent for {agent}"
    );
    assert!(!workspace.path().join(".stove-dashboard.db").exists());
  }
}

#[test]
fn dry_run_does_not_download_or_write_even_when_source_is_missing() {
  let workspace = TempDir::new().unwrap();
  let output = install(
    workspace.path(),
    &workspace.path().join("missing"),
    &[
      "--path",
      ".",
      "--agent",
      "claude",
      "--configure-mcp",
      "--dry-run",
    ],
  );
  assert_success(&output);
  assert!(String::from_utf8_lossy(&output.stdout).contains(".mcp.json"));
  assert_eq!(fs::read_dir(workspace.path()).unwrap().count(), 0);
}

#[test]
fn malformed_config_fails_before_replacing_skills() {
  let workspace = TempDir::new().unwrap();
  let source = TempDir::new().unwrap();
  fixture(source.path());
  let target = workspace.path().join(".claude/skills/stove");
  fs::create_dir_all(&target).unwrap();
  fs::write(target.join("SKILL.md"), "local customizations").unwrap();
  fs::write(workspace.path().join(".mcp.json"), "{broken").unwrap();
  let output = install(
    workspace.path(),
    source.path(),
    &["--path", ".", "--agent", "claude", "--configure-mcp"],
  );
  assert!(!output.status.success());
  assert_eq!(
    fs::read_to_string(target.join("SKILL.md")).unwrap(),
    "local customizations"
  );
  assert_eq!(
    fs::read_to_string(workspace.path().join(".mcp.json")).unwrap(),
    "{broken"
  );
}

#[test]
fn global_claude_install_uses_home_without_changing_project() {
  let workspace = TempDir::new().unwrap();
  let home = TempDir::new().unwrap();
  let source = TempDir::new().unwrap();
  fixture(source.path());
  fs::write(
    home.path().join(".claude.json"),
    "{\"theme\":\"dark\",\"mcpServers\":{}}",
  )
  .unwrap();
  let output = Command::new(env!("CARGO_BIN_EXE_stove"))
    .current_dir(workspace.path())
    .env("HOME", home.path())
    .args([
      "skills",
      "install",
      "--global",
      "--agent",
      "claude",
      "--configure-mcp",
      "--source",
    ])
    .arg(source.path())
    .output()
    .unwrap();
  assert_success(&output);
  assert!(home.path().join(".claude/skills/stove/SKILL.md").is_file());
  let configured = fs::read_to_string(home.path().join(".claude.json")).unwrap();
  assert!(configured.contains("dark"));
  assert!(configured.contains("http://localhost:4040/mcp"));
  assert_eq!(fs::read_dir(workspace.path()).unwrap().count(), 0);
}

#[test]
fn global_provider_destinations_and_environment_overrides() {
  for (agent, override_directory, relative_config) in [
    ("codex", false, ".codex/config.toml"),
    ("codex", true, "config.toml"),
    ("cursor", false, ".cursor/mcp.json"),
    ("opencode", false, ".config/opencode/opencode.json"),
    ("opencode", true, "opencode/opencode.jsonc"),
  ] {
    let workspace = TempDir::new().unwrap();
    let home = TempDir::new().unwrap();
    let override_root = TempDir::new().unwrap();
    let source = TempDir::new().unwrap();
    fixture(source.path());
    let config_root = if override_directory {
      override_root.path()
    } else {
      home.path()
    };
    let config_path = config_root.join(relative_config);
    if relative_config.ends_with(".jsonc") {
      fs::create_dir_all(config_path.parent().unwrap()).unwrap();
      fs::write(&config_path, "{}").unwrap();
    }
    let mut command = Command::new(env!("CARGO_BIN_EXE_stove"));
    command
      .current_dir(workspace.path())
      .env("HOME", home.path())
      .env_remove("CODEX_HOME")
      .env_remove("XDG_CONFIG_HOME")
      .args([
        "skills",
        "install",
        "--global",
        "--agent",
        agent,
        "--configure-mcp",
        "--source",
      ])
      .arg(source.path());
    if override_directory {
      command.env(
        if agent == "codex" {
          "CODEX_HOME"
        } else {
          "XDG_CONFIG_HOME"
        },
        override_root.path(),
      );
    }
    assert_success(&command.output().unwrap());
    assert!(home.path().join(".agents/skills/stove/SKILL.md").is_file());
    let configured = fs::read_to_string(&config_path).unwrap();
    if agent == "codex" {
      let document = configured.parse::<toml_edit::DocumentMut>().unwrap();
      assert_eq!(
        document["mcp_servers"]["stove"]["url"].as_str(),
        Some("http://localhost:4040/mcp")
      );
      assert_eq!(
        document["mcp_servers"]["stove"]["enabled"].as_bool(),
        Some(true)
      );
    } else {
      let document: serde_json::Value = serde_json::from_str(&configured).unwrap();
      let key = if agent == "opencode" {
        "mcp"
      } else {
        "mcpServers"
      };
      assert_eq!(document[key]["stove"]["url"], "http://localhost:4040/mcp");
      assert_eq!(
        document[key]["stove"]["type"],
        if agent == "opencode" {
          "remote"
        } else {
          "http"
        }
      );
    }
    assert_eq!(fs::read_dir(workspace.path()).unwrap().count(), 0);
    if override_directory {
      let default_config = home.path().join(if agent == "codex" {
        ".codex/config.toml"
      } else {
        ".config/opencode/opencode.json"
      });
      assert!(!default_config.exists());
    }
  }
}

#[test]
fn invalid_destination_options_and_urls_fail_without_writes() {
  let workspace = TempDir::new().unwrap();
  let source = TempDir::new().unwrap();
  fixture(source.path());
  for args in [
    vec!["--global", "--path", "."],
    vec!["--force", "--path", "."],
    vec!["--path", ".", "--configure-mcp"],
    vec![
      "--path",
      ".",
      "--agent",
      "codex",
      "--configure-mcp",
      "--mcp-url",
      "file:///tmp/socket",
    ],
  ] {
    assert!(
      !install(workspace.path(), source.path(), &args)
        .status
        .success()
    );
    assert_eq!(fs::read_dir(workspace.path()).unwrap().count(), 0);
  }
}
