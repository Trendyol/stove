//! Stove agent skills sync.
//!
//! Discovers the local Stove skill directory under a project (preferring
//! `.agents/skills/stove`, falling back to `.claude/skills/stove` and
//! `.agent/skills/stove`), compares it against the canonical copy on GitHub,
//! and offers to install or update.
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

use crate::config::{Config, SkillsCommand, StoveCommand};

/// GitHub source coordinates and HTTP defaults.
mod github {
  use std::time::Duration;

  pub const REPO_OWNER: &str = "Trendyol";
  pub const REPO_NAME: &str = "stove";
  pub const REPO_REF: &str = "main";
  pub const USER_AGENT: &str = concat!("stove-cli/", env!("STOVE_VERSION"));
  /// Capped low so a slow GitHub call cannot stall server bind on cold start.
  pub const REQUEST_TIMEOUT: Duration = Duration::from_secs(5);
}

/// Candidate skill directory paths, probed in order both locally and remotely.
/// First entry is the preferred vendor-neutral path used as the install
/// default when nothing exists yet.
const SKILL_PATHS: &[&str] = &[
  ".agents/skills/stove",
  ".claude/skills/stove",
  ".agent/skills/stove",
];

/// Handle a `skills` subcommand if one was requested.
///
/// Returns `Ok(true)` when a subcommand was handled and the CLI should exit;
/// `Ok(false)` when no subcommand was specified.
pub async fn handle_skills_command(config: &Config) -> anyhow::Result<bool> {
  let Some(StoveCommand::Skills { command }) = &config.command else {
    return Ok(false);
  };
  match command {
    SkillsCommand::Install { force } => install_skills_command(*force).await?,
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
    println!(
      "  Stove skills can be installed from your project root: cd <your-repo> && stove skills install"
    );
    return;
  };

  let target = resolve_local_target(&repo_root);

  match decide_sync_action(&target, config.update_skills).await {
    SyncAction::Skip(reason) => {
      if let Some(message) = reason {
        eprintln!("  warning: skipping Stove skills check ({message})");
      }
    }
    SyncAction::Apply(remote) => apply_install(&target, &remote),
    SyncAction::Prompt(remote) => {
      match prompt_yes_no("  Install/update Stove agent skills from GitHub? [y/N] ") {
        Ok(true) => apply_install(&target, &remote),
        Ok(false) => {}
        Err(err) => eprintln!("  warning: skills prompt failed: {err}"),
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
    Ok(()) => println!("  Updated Stove agent skills at {}", target.display()),
    Err(err) => eprintln!("  warning: failed to install Stove skills: {err}"),
  }
}

/// `stove skills install` execution path.
///
/// Without `--force`: requires a git repository and installs into the resolved
/// target under the repo root. With `--force`: skips git detection and
/// installs into the resolved target relative to the current directory.
async fn install_skills_command(force: bool) -> anyhow::Result<()> {
  let cwd = std::env::current_dir()?;
  let target = if force {
    resolve_local_target(&cwd)
  } else {
    let repo_root = find_git_root(&cwd).ok_or_else(|| {
      anyhow::anyhow!(
        "stove skills install must be run inside a git repository (use --force to install in the current directory)"
      )
    })?;
    resolve_local_target(&repo_root)
  };

  let remote = fetch_remote_skills().await?;
  if remote.is_empty() {
    anyhow::bail!("no Stove skills found in remote repository at any known path");
  }

  install_skills(&target, &remote)?;
  println!(
    "Installed {} skill files at {}",
    remote.len(),
    target.display()
  );
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
/// Picks the first existing skill directory in [`SKILL_PATHS`]. If none
/// exist, returns the first candidate (the vendor-neutral default).
#[must_use]
pub fn resolve_local_target(root: &Path) -> PathBuf {
  SKILL_PATHS
    .iter()
    .map(|candidate| root.join(candidate))
    .find(|path| path.is_dir())
    .unwrap_or_else(|| root.join(SKILL_PATHS[0]))
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

/// Fetch the Stove skill files from GitHub.
///
/// Probes [`SKILL_PATHS`] in order and uses the first path that returns a
/// non-empty directory listing.
async fn fetch_remote_skills() -> anyhow::Result<RemoteSkills> {
  let client = reqwest::Client::builder()
    .user_agent(github::USER_AGENT)
    .timeout(github::REQUEST_TIMEOUT)
    .build()?;

  for remote_path in SKILL_PATHS {
    match fetch_remote_skills_for_path(&client, remote_path).await {
      Ok(snapshot) if !snapshot.is_empty() => return Ok(snapshot),
      Ok(_) => {}
      Err(err) => tracing::debug!("remote skills probe failed for {remote_path}: {err}"),
    }
  }
  anyhow::bail!("no Stove skills found in remote repository at any known path");
}

async fn fetch_remote_skills_for_path(
  client: &reqwest::Client,
  remote_path: &str,
) -> anyhow::Result<RemoteSkills> {
  let listing_url = format!(
    "https://api.github.com/repos/{owner}/{repo}/contents/{path}?ref={ref_}",
    owner = github::REPO_OWNER,
    repo = github::REPO_NAME,
    path = remote_path,
    ref_ = github::REPO_REF,
  );
  let response = client.get(&listing_url).send().await?;
  if response.status() == reqwest::StatusCode::NOT_FOUND {
    return Ok(RemoteSkills::default());
  }
  let entries: Vec<ContentsEntry> = response.error_for_status()?.json().await?;

  let mut downloads: JoinSet<anyhow::Result<(String, Vec<u8>)>> = JoinSet::new();
  for entry in entries {
    if entry.kind != "file" {
      continue;
    }
    let Some(url) = entry.download_url else {
      continue;
    };
    let client = client.clone();
    let name = entry.name;
    downloads.spawn(async move {
      let body = client
        .get(&url)
        .send()
        .await?
        .error_for_status()?
        .bytes()
        .await?;
      Ok((name, body.to_vec()))
    });
  }

  let mut files = BTreeMap::new();
  while let Some(result) = downloads.join_next().await {
    let (name, bytes) = result??;
    files.insert(name, bytes);
  }
  Ok(RemoteSkills { files })
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
  if !target.is_dir() {
    return None;
  }
  std::fs::read_dir(target)
    .ok()?
    .flatten()
    .map(|entry| entry.path())
    .filter(|path| path.is_file())
    .map(|path| {
      let name = path.file_name()?.to_str()?.to_string();
      let bytes = std::fs::read(&path).ok()?;
      Some((name, bytes))
    })
    .collect()
}

/// Replace the target directory with the remote snapshot.
///
/// Writes files into a sibling staging directory first, then performs a
/// best-effort atomic swap (move-aside-old + rename-staging-into-place).
pub fn install_skills(target: &Path, remote: &RemoteSkills) -> anyhow::Result<()> {
  let parent = target
    .parent()
    .ok_or_else(|| anyhow::anyhow!("invalid skills target: {}", target.display()))?;
  std::fs::create_dir_all(parent)?;

  let target_name = target
    .file_name()
    .and_then(|n| n.to_str())
    .unwrap_or("stove");
  let timestamp = chrono::Local::now().format("%Y%m%d-%H%M%S%3f");
  let staging = parent.join(format!(".{target_name}-staging-{timestamp}"));
  let aside = parent.join(format!(".{target_name}-old-{timestamp}"));

  write_staging(&staging, remote)?;
  swap_into_place(&staging, target, &aside)
}

fn write_staging(staging: &Path, remote: &RemoteSkills) -> anyhow::Result<()> {
  // remove_dir_all on a missing path returns NotFound — discard intentionally.
  let _ = std::fs::remove_dir_all(staging);
  std::fs::create_dir_all(staging)?;
  for (name, bytes) in remote.iter() {
    std::fs::write(staging.join(name), bytes)?;
  }
  Ok(())
}

fn swap_into_place(staging: &Path, target: &Path, aside: &Path) -> anyhow::Result<()> {
  let target_existed = target.exists();
  if target_existed {
    std::fs::rename(target, aside)?;
  }

  match std::fs::rename(staging, target) {
    Ok(()) => {
      if target_existed {
        let _ = std::fs::remove_dir_all(aside);
      }
      Ok(())
    }
    Err(err) => {
      if target_existed {
        let _ = std::fs::rename(aside, target);
      }
      let _ = std::fs::remove_dir_all(staging);
      Err(anyhow::anyhow!("failed to install skills: {err}"))
    }
  }
}

fn prompt_yes_no(message: &str) -> anyhow::Result<bool> {
  print!("{message}");
  std::io::stdout().flush()?;
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
  fn resolve_local_target_falls_back_to_claude() {
    let dir = TempDir::new().unwrap();
    let claude = dir.path().join(".claude/skills/stove");
    fs::create_dir_all(&claude).unwrap();

    let target = resolve_local_target(dir.path());
    assert_eq!(target, claude);
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
