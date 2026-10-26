//! Stove agent skills sync.
//!
//! Installs under `.agents/skills/stove`, compares that directory against the
//! canonical copy on GitHub, and offers to install or update. Legacy local
//! directories are left untouched; older remote paths remain download fallbacks.
//!
//! Network and prompt behavior is conservative by default:
//! - never modifies anything without explicit user consent on a TTY
//! - falls back to a non-blocking warning on network/API errors
//! - skips entirely when started outside a git repository on plain `stove`

use std::collections::BTreeMap;
use std::io::{IsTerminal, Write};
use std::path::{Path, PathBuf};

use serde::Deserialize;
use tokio::task::JoinSet;

use crate::config::{Config, SkillInstallOptions, SkillsCommand, StoveCommand};

/// GitHub source coordinates and HTTP defaults.
mod github {
  use std::time::Duration;

  pub const REPO_OWNER: &str = "Trendyol";
  pub const REPO_NAME: &str = "stove";
  pub const REPO_REF: &str = "main";
  pub const USER_AGENT: &str = concat!("stove-server/", env!("STOVE_VERSION"));
  /// Capped low so a slow GitHub call cannot stall server bind on cold start.
  pub const REQUEST_TIMEOUT: Duration = Duration::from_secs(5);
}

const SKILL_PATH: &str = ".agents/skills/stove";

/// Remote paths, in preference order. Keep legacy fallbacks so installation
/// works while the canonical directory rename is being rolled out on GitHub.
const REMOTE_SKILL_PATHS: &[&str] = &[SKILL_PATH, ".claude/skills/stove", ".agent/skills/stove"];

/// Handle a `skills` subcommand if one was requested.
///
/// Returns `Ok(true)` when a subcommand was handled and the server should exit;
/// `Ok(false)` when no subcommand was specified.
pub async fn handle_skills_command(config: &Config) -> anyhow::Result<bool> {
  let Some(StoveCommand::Skills { command }) = &config.command else {
    return Ok(false);
  };
  match command {
    SkillsCommand::Install(options) => install_skills_command(options).await?,
  }
  Ok(true)
}

/// Suggest or apply a skills update during normal startup.
///
/// Network and IO failures are reported and never abort startup.
pub async fn maybe_update_skills(config: &Config) {
  if config.no_skills_check {
    return;
  }

  let Some(repo_root) = current_git_root() else {
    tracing::info!(
      "Stove skills can be installed from your project root: cd <your-repo> && stove skills install"
    );
    return;
  };

  let target = resolve_local_target(&repo_root);

  match decide_sync_action(&target, config.update_skills).await {
    SyncAction::Skip(reason) => {
      if let Some(message) = reason {
        tracing::warn!(reason = %message, "Skipping Stove skills check");
      }
    }
    SyncAction::Apply(remote) => apply_install(&target, &remote),
    SyncAction::Prompt(remote) => {
      match prompt_yes_no("  Install/update Stove agent skills from GitHub? [y/N] ") {
        Ok(true) => apply_install(&target, &remote),
        Ok(false) => {}
        Err(err) => tracing::warn!(error = %err, "Skills prompt failed"),
      }
    }
  }
}

/// What to do once we know the local target and the remote snapshot.
enum SyncAction {
  /// Nothing to do. `Some(reason)` surfaces a recoverable error to the user.
  Skip(Option<String>),
  /// Install without prompting (`--update-skills` or non-TTY in some flows).
  Apply(RemoteSkills),
  /// TTY user-facing prompt path.
  Prompt(RemoteSkills),
}

async fn decide_sync_action(target: &Path, force_update: bool) -> SyncAction {
  let remote = match fetch_remote_skills().await {
    Ok(remote) => remote,
    Err(err) => return SyncAction::Skip(Some(err.to_string())),
  };
  if remote.is_empty() || skills_match(target, &remote) {
    return SyncAction::Skip(None);
  }
  if force_update {
    SyncAction::Apply(remote)
  } else if std::io::stdin().is_terminal() {
    SyncAction::Prompt(remote)
  } else {
    SyncAction::Skip(None)
  }
}

fn apply_install(target: &Path, remote: &RemoteSkills) {
  match install_skills(target, remote) {
    Ok(()) => {
      let target = target.display();
      tracing::info!(%target, "Updated Stove agent skills");
    }
    Err(err) => tracing::warn!(error = %err, "Failed to install Stove skills"),
  }
}

/// Install the shared skill, with optional agent-specific MCP configuration.
/// Resolve and validate the complete plan before downloading or changing files.
async fn install_skills_command(options: &SkillInstallOptions) -> anyhow::Result<()> {
  let plan = crate::agent_setup::InstallPlan::resolve(options)?;
  plan.describe();
  if options.dry_run {
    println!("Dry run: no files changed.");
    return Ok(());
  }

  let remote = if let Some(source) = &options.source {
    RemoteSkills {
      files: read_local_files(source).ok_or_else(|| {
        anyhow::anyhow!(
          "cannot read skill directory {} (symlinks are not supported)",
          source.display()
        )
      })?,
    }
  } else {
    fetch_remote_skills().await?
  };
  if !remote.files.contains_key("SKILL.md") {
    anyhow::bail!("the skill source must contain SKILL.md");
  }

  install_skills(&plan.skill_target, &remote)?;
  plan.write_mcp_config()?;
  println!(
    "Installed {} skill files at {}",
    remote.len(),
    plan.skill_target.display()
  );
  println!("Restart your agent session to discover the skill.");
  Ok(())
}

fn current_git_root() -> Option<PathBuf> {
  let cwd = std::env::current_dir().ok()?;
  find_git_root(&cwd)
}

/// Walk up from `start` until a directory containing a `.git` entry is found.
/// `.git` may be a directory (regular repo) or a file (worktree / submodule).
#[must_use]
pub fn find_git_root(start: &Path) -> Option<PathBuf> {
  start
    .ancestors()
    .find(|dir| dir.join(".git").exists())
    .map(Path::to_path_buf)
}

/// Resolve the local skill target for installation.
///
/// Always use the canonical vendor-neutral path. Existing legacy skills may
/// contain project customizations, so neither overwrite nor remove them.
#[must_use]
pub fn resolve_local_target(root: &Path) -> PathBuf {
  root.join(SKILL_PATH)
}

/// Snapshot of the canonical Stove skill directory on GitHub.
#[derive(Debug, Clone, Default)]
pub struct RemoteSkills {
  files: BTreeMap<String, Vec<u8>>,
}

impl RemoteSkills {
  #[must_use]
  pub fn is_empty(&self) -> bool {
    self.files.is_empty()
  }

  #[must_use]
  pub fn len(&self) -> usize {
    self.files.len()
  }

  pub fn iter(&self) -> impl Iterator<Item = (&String, &Vec<u8>)> {
    self.files.iter()
  }
}

#[derive(Debug, Deserialize)]
struct ContentsEntry {
  name: String,
  #[serde(rename = "type")]
  kind: String,
  download_url: Option<String>,
}

impl ContentsEntry {
  fn relative_path(&self, parent: &str) -> anyhow::Result<String> {
    validate_resource_path(&self.name)?;
    if self.name.contains('/') {
      anyhow::bail!("invalid GitHub directory entry: {}", self.name);
    }
    Ok(if parent.is_empty() {
      self.name.clone()
    } else {
      format!("{parent}/{}", self.name)
    })
  }
}

/// Fetch the canonical skill. Only a missing directory permits a legacy fallback;
/// partial downloads and API failures must not silently install older content.
async fn fetch_remote_skills() -> anyhow::Result<RemoteSkills> {
  let client = reqwest::Client::builder()
    .user_agent(github::USER_AGENT)
    .timeout(github::REQUEST_TIMEOUT)
    .build()?;

  for remote_path in REMOTE_SKILL_PATHS {
    let snapshot = fetch_remote_skills_for_path(&client, remote_path).await?;
    if !snapshot.is_empty() {
      return Ok(snapshot);
    }
  }
  anyhow::bail!("no Stove skills found in remote repository at any known path");
}

async fn fetch_remote_skills_for_path(
  client: &reqwest::Client,
  remote_path: &str,
) -> anyhow::Result<RemoteSkills> {
  let contents_url = format!(
    "https://api.github.com/repos/{}/{}/contents/{}",
    github::REPO_OWNER,
    github::REPO_NAME,
    remote_path,
  );
  fetch_skill_tree(client, &contents_url).await
}

async fn download_file(
  client: reqwest::Client,
  name: String,
  url: String,
) -> anyhow::Result<(String, Vec<u8>)> {
  let body = client
    .get(url)
    .send()
    .await?
    .error_for_status()?
    .bytes()
    .await?;
  Ok((name, body.to_vec()))
}

/// Fetch every resource beneath the skill, including nested references and scripts.
async fn fetch_skill_tree(
  client: &reqwest::Client,
  contents_url: &str,
) -> anyhow::Result<RemoteSkills> {
  let mut directories = vec![String::new()];
  let mut downloads: JoinSet<anyhow::Result<(String, Vec<u8>)>> = JoinSet::new();
  while let Some(relative) = directories.pop() {
    let mut url = reqwest::Url::parse(contents_url)?;
    if !relative.is_empty() {
      url
        .path_segments_mut()
        .map_err(|()| anyhow::anyhow!("invalid contents URL"))?
        .extend(relative.split('/'));
    }
    url.query_pairs_mut().append_pair("ref", github::REPO_REF);
    let response = client.get(url).send().await?;
    if response.status() == reqwest::StatusCode::NOT_FOUND && relative.is_empty() {
      return Ok(RemoteSkills::default());
    }
    let entries: Vec<ContentsEntry> = response.error_for_status()?.json().await?;
    for entry in entries {
      let name = entry.relative_path(&relative)?;
      match entry.kind.as_str() {
        "dir" => directories.push(name),
        "file" => {
          let url = entry
            .download_url
            .ok_or_else(|| anyhow::anyhow!("missing download URL for {name}"))?;
          downloads.spawn(download_file(client.clone(), name, url));
        }
        other => anyhow::bail!("unsupported skill resource type {other}: {name}"),
      }
    }
  }

  let mut files = BTreeMap::new();
  while let Some(result) = downloads.join_next().await {
    let (name, bytes) = result??;
    files.insert(name, bytes);
  }
  if !files.contains_key("SKILL.md") {
    anyhow::bail!("remote skill directory does not contain SKILL.md");
  }
  Ok(RemoteSkills { files })
}

/// Resource paths must stay inside the skill on both Unix and Windows.
fn validate_resource_path(name: &str) -> anyhow::Result<()> {
  if name.is_empty()
    || name.contains(['\\', ':'])
    || name
      .split('/')
      .any(|part| part.is_empty() || part == "." || part == "..")
  {
    anyhow::bail!("invalid skill resource path: {name}");
  }
  Ok(())
}

/// Compare an existing local target directory against a remote snapshot.
///
/// Matches when the target exists and contains exactly the same set of files
/// with byte-identical contents. Local files outside the remote set are
/// considered drift and force a mismatch (so a clean install replaces them).
#[must_use]
pub fn skills_match(target: &Path, remote: &RemoteSkills) -> bool {
  let Some(local) = read_local_files(target) else {
    return false;
  };
  local == remote.files
}

fn read_local_files(target: &Path) -> Option<BTreeMap<String, Vec<u8>>> {
  let mut files = BTreeMap::new();
  let mut directories = vec![target.to_path_buf()];
  while let Some(directory) = directories.pop() {
    if !std::fs::symlink_metadata(&directory).ok()?.is_dir() {
      return None;
    }
    for entry in std::fs::read_dir(&directory).ok()? {
      let entry = entry.ok()?;
      let kind = entry.file_type().ok()?;
      let path = entry.path();
      if kind.is_dir() {
        directories.push(path);
      } else if kind.is_file() {
        let relative = path.strip_prefix(target).ok()?.to_str()?.replace('\\', "/");
        files.insert(relative, std::fs::read(&path).ok()?);
      } else {
        return None;
      }
    }
  }
  Some(files)
}

/// Replace the target directory with the remote snapshot.
///
/// Writes files into a sibling staging directory first, then performs a
/// best-effort atomic swap (move-aside-old + rename-staging-into-place).
pub fn install_skills(target: &Path, remote: &RemoteSkills) -> anyhow::Result<()> {
  remote
    .iter()
    .try_for_each(|(name, _)| validate_resource_path(name))?;
  if target.is_symlink() {
    anyhow::bail!(
      "refusing to replace symlinked skill directory: {}",
      target.display()
    );
  }
  let parent = target
    .parent()
    .ok_or_else(|| anyhow::anyhow!("invalid skills target: {}", target.display()))?;
  std::fs::create_dir_all(parent)?;

  let target_name = target
    .file_name()
    .and_then(|n| n.to_str())
    .unwrap_or("stove");
  let timestamp = uuid::Uuid::new_v4();
  let staging = parent.join(format!(".{target_name}-staging-{timestamp}"));
  let aside = parent.join(format!(".{target_name}-old-{timestamp}"));

  let result =
    write_staging(&staging, remote).and_then(|()| swap_into_place(&staging, target, &aside));
  if result.is_err() {
    let _ = std::fs::remove_dir_all(&staging);
  }
  result
}

fn write_staging(staging: &Path, remote: &RemoteSkills) -> anyhow::Result<()> {
  std::fs::create_dir(staging)?;
  for (name, bytes) in remote.iter() {
    let path = staging.join(name);
    if let Some(parent) = path.parent() {
      std::fs::create_dir_all(parent)?;
    }
    std::fs::write(path, bytes)?;
  }
  Ok(())
}

fn swap_into_place(staging: &Path, target: &Path, aside: &Path) -> anyhow::Result<()> {
  let target_existed = target.exists();
  if target_existed {
    std::fs::rename(target, aside)?;
  }
  if let Err(error) = std::fs::rename(staging, target) {
    if target_existed {
      let _ = std::fs::rename(aside, target);
    }
    return Err(anyhow::anyhow!("failed to install skills: {error}"));
  }
  if target_existed {
    let _ = std::fs::remove_dir_all(aside);
  }
  Ok(())
}

fn prompt_yes_no(message: &str) -> anyhow::Result<bool> {
  eprint!("{message}");
  std::io::stderr().flush()?;
  let mut input = String::new();
  std::io::stdin().read_line(&mut input)?;
  Ok(matches!(
    input.trim().to_ascii_lowercase().as_str(),
    "y" | "yes"
  ))
}

#[cfg(test)]
mod tests {
  use super::*;
  use std::fs;
  use tempfile::TempDir;

  fn remote_with(files: &[(&str, &[u8])]) -> RemoteSkills {
    RemoteSkills {
      files: files
        .iter()
        .map(|(name, bytes)| ((*name).to_string(), bytes.to_vec()))
        .collect(),
    }
  }

  #[test]
  fn nested_resources_are_installed_compared_and_removed_on_update() {
    let dir = TempDir::new().unwrap();
    let target = dir.path().join("stove");
    let remote = remote_with(&[
      ("SKILL.md", b"skill"),
      ("references/setup.md", b"guide"),
      ("scripts/check.py", b"print('ok')"),
    ]);
    install_skills(&target, &remote).unwrap();
    assert!(skills_match(&target, &remote));
    fs::write(target.join("references/setup.md"), b"changed").unwrap();
    assert!(!skills_match(&target, &remote));
    install_skills(&target, &remote_with(&[("SKILL.md", b"new")])).unwrap();
    assert!(!target.join("references").exists());
  }

  #[test]
  fn invalid_resource_paths_leave_existing_installation_intact() {
    let dir = TempDir::new().unwrap();
    let target = dir.path().join("stove");
    fs::create_dir(&target).unwrap();
    fs::write(target.join("SKILL.md"), b"keep").unwrap();
    for name in [
      "../outside",
      "/absolute",
      "a/../../outside",
      "a\\..\\outside",
      "C:/outside",
      "a//b",
    ] {
      assert!(install_skills(&target, &remote_with(&[(name, b"bad")])).is_err());
      assert_eq!(fs::read(target.join("SKILL.md")).unwrap(), b"keep");
    }
    assert_eq!(fs::read_dir(dir.path()).unwrap().count(), 1);
  }

  #[cfg(unix)]
  #[test]
  fn symlinked_skill_directories_are_not_replaced_or_followed() {
    let dir = TempDir::new().unwrap();
    let original = dir.path().join("original");
    fs::create_dir(&original).unwrap();
    fs::write(original.join("SKILL.md"), b"keep").unwrap();
    let link = dir.path().join("link");
    std::os::unix::fs::symlink(&original, &link).unwrap();
    assert!(read_local_files(&link).is_none());
    assert!(install_skills(&link, &remote_with(&[("SKILL.md", b"bad")])).is_err());
    assert_eq!(fs::read(original.join("SKILL.md")).unwrap(), b"keep");
  }

  #[tokio::test]
  async fn github_download_includes_nested_resources_and_propagates_failures() {
    use axum::{Json, Router, routing::get};
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let origin = format!("http://{}", listener.local_addr().unwrap());
    let root_listing = serde_json::json!([
      {"name": "SKILL.md", "type": "file", "download_url": format!("{origin}/raw/skill")},
      {"name": "references", "type": "dir", "download_url": null}
    ]);
    let nested_listing = serde_json::json!([
      {"name": "setup.md", "type": "file", "download_url": format!("{origin}/raw/setup")}
    ]);
    let bad_listing = serde_json::json!([
      {"name": "SKILL.md", "type": "file", "download_url": format!("{origin}/missing")}
    ]);
    let router = Router::new()
      .route("/contents", get(move || async { Json(root_listing) }))
      .route(
        "/contents/references",
        get(move || async { Json(nested_listing) }),
      )
      .route("/broken", get(move || async { Json(bad_listing) }))
      .route("/raw/skill", get(|| async { "skill" }))
      .route("/raw/setup", get(|| async { "guide" }));
    let server = tokio::spawn(async { axum::serve(listener, router).await.unwrap() });
    let client = reqwest::Client::new();
    let remote = fetch_skill_tree(&client, &format!("{origin}/contents"))
      .await
      .unwrap();
    assert_eq!(
      remote.files,
      remote_with(&[("SKILL.md", b"skill"), ("references/setup.md", b"guide")]).files
    );
    assert!(
      fetch_skill_tree(&client, &format!("{origin}/broken"))
        .await
        .is_err()
    );
    assert!(
      fetch_skill_tree(&client, &format!("{origin}/absent"))
        .await
        .unwrap()
        .is_empty()
    );
    server.abort();
  }

  #[test]
  fn find_git_root_detects_directory() {
    let dir = TempDir::new().unwrap();
    fs::create_dir(dir.path().join(".git")).unwrap();
    let nested = dir.path().join("a/b/c");
    fs::create_dir_all(&nested).unwrap();

    let root = find_git_root(&nested).unwrap();
    assert_eq!(root, dir.path());
  }

  #[test]
  fn find_git_root_detects_file_marker() {
    let dir = TempDir::new().unwrap();
    fs::write(dir.path().join(".git"), "gitdir: /elsewhere\n").unwrap();
    let nested = dir.path().join("nested");
    fs::create_dir_all(&nested).unwrap();

    let root = find_git_root(&nested).unwrap();
    assert_eq!(root, dir.path());
  }

  #[test]
  fn find_git_root_returns_none_when_absent() {
    let dir = TempDir::new().unwrap();
    let nested = dir.path().join("a/b");
    fs::create_dir_all(&nested).unwrap();
    assert!(find_git_root(&nested).is_none());
  }

  #[test]
  fn resolve_local_target_prefers_existing_agents() {
    let dir = TempDir::new().unwrap();
    let agents = dir.path().join(".agents/skills/stove");
    fs::create_dir_all(&agents).unwrap();
    fs::create_dir_all(dir.path().join(".claude/skills/stove")).unwrap();

    let target = resolve_local_target(dir.path());
    assert_eq!(target, agents);
  }

  #[test]
  fn install_uses_agents_and_preserves_legacy_skills() {
    let dir = TempDir::new().unwrap();
    for legacy_path in [".claude/skills/stove", ".agent/skills/stove"] {
      let legacy = dir.path().join(legacy_path);
      fs::create_dir_all(&legacy).unwrap();
      fs::write(legacy.join("SKILL.md"), b"project customizations").unwrap();

      let target = resolve_local_target(dir.path());
      assert_eq!(target, dir.path().join(".agents/skills/stove"));
      let remote = remote_with(&[("SKILL.md", b"canonical skill")]);
      install_skills(&target, &remote).unwrap();

      assert!(skills_match(&target, &remote));
      assert_eq!(
        fs::read(legacy.join("SKILL.md")).unwrap(),
        b"project customizations"
      );
      fs::remove_dir_all(&target).unwrap();
    }
  }

  #[test]
  fn resolve_local_target_defaults_to_agents_when_none_exist() {
    let dir = TempDir::new().unwrap();
    let target = resolve_local_target(dir.path());
    assert_eq!(target, dir.path().join(".agents/skills/stove"));
  }

  #[test]
  fn skills_match_detects_missing_target() {
    let dir = TempDir::new().unwrap();
    let target = dir.path().join("missing");
    let remote = remote_with(&[("a.md", b"hello")]);
    assert!(!skills_match(&target, &remote));
  }

  #[test]
  fn skills_match_returns_true_for_identical_dirs() {
    let dir = TempDir::new().unwrap();
    let target = dir.path().join("local");
    fs::create_dir_all(&target).unwrap();
    fs::write(target.join("a.md"), b"hello").unwrap();
    fs::write(target.join("b.md"), b"world").unwrap();

    let remote = remote_with(&[("a.md", b"hello"), ("b.md", b"world")]);
    assert!(skills_match(&target, &remote));
  }

  #[test]
  fn skills_match_detects_content_drift() {
    let dir = TempDir::new().unwrap();
    let target = dir.path().join("local");
    fs::create_dir_all(&target).unwrap();
    fs::write(target.join("a.md"), b"old content").unwrap();
    let remote = remote_with(&[("a.md", b"new content")]);
    assert!(!skills_match(&target, &remote));
  }

  #[test]
  fn skills_match_detects_extra_local_file() {
    let dir = TempDir::new().unwrap();
    let target = dir.path().join("local");
    fs::create_dir_all(&target).unwrap();
    fs::write(target.join("a.md"), b"hello").unwrap();
    fs::write(target.join("stale.md"), b"orphan").unwrap();
    let remote = remote_with(&[("a.md", b"hello")]);
    assert!(!skills_match(&target, &remote));
  }

  #[test]
  fn install_skills_creates_target_when_missing() {
    let dir = TempDir::new().unwrap();
    let target = dir.path().join("nested/.agents/skills/stove");
    let remote = remote_with(&[("a.md", b"hello"), ("b.md", b"world")]);

    install_skills(&target, &remote).unwrap();

    assert_eq!(fs::read(target.join("a.md")).unwrap(), b"hello");
    assert_eq!(fs::read(target.join("b.md")).unwrap(), b"world");
  }

  #[test]
  fn install_skills_replaces_existing_target() {
    let dir = TempDir::new().unwrap();
    let target = dir.path().join(".agents/skills/stove");
    fs::create_dir_all(&target).unwrap();
    fs::write(target.join("old.md"), b"obsolete").unwrap();
    fs::write(target.join("a.md"), b"old").unwrap();

    let remote = remote_with(&[("a.md", b"new"), ("b.md", b"fresh")]);
    install_skills(&target, &remote).unwrap();

    assert_eq!(fs::read(target.join("a.md")).unwrap(), b"new");
    assert_eq!(fs::read(target.join("b.md")).unwrap(), b"fresh");
    assert!(!target.join("old.md").exists());
  }

  #[test]
  fn install_skills_cleans_up_staging_artifacts() {
    let dir = TempDir::new().unwrap();
    let target = dir.path().join(".agents/skills/stove");
    let remote = remote_with(&[("a.md", b"hello")]);
    install_skills(&target, &remote).unwrap();

    let parent = target.parent().unwrap();
    let leftovers: Vec<_> = fs::read_dir(parent)
      .unwrap()
      .flatten()
      .filter(|entry| {
        let name = entry.file_name().to_string_lossy().into_owned();
        name.contains("-staging-") || name.contains("-old-")
      })
      .collect();
    assert!(leftovers.is_empty(), "staging dirs should be removed");
  }
}
