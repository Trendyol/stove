//! Resolve standalone skill installation and delegate agent conventions to providers.

pub(crate) mod catalog;
mod config_file;
mod formats;
mod providers;

use std::path::{Path, PathBuf};

use anyhow::{Context, Result};

use crate::config::SkillInstallOptions;
use config_file::ConfigEdit;

const DEFAULT_MCP_URL: &str = "http://localhost:4040/mcp";

pub(crate) struct InstallPlan {
  pub skill_target: PathBuf,
  mcp: Option<ConfigEdit>,
}

impl InstallPlan {
  pub fn resolve(options: &SkillInstallOptions) -> Result<Self> {
    let cwd = std::env::current_dir()?;
    let home = std::env::var_os("HOME")
      .or_else(|| std::env::var_os("USERPROFILE"))
      .map(PathBuf::from);
    let root = install_root(options, &cwd, home.as_deref())?;
    let provider = providers::provider(options.agent);
    let skill_target = root.join(provider.skill_directory());
    let mcp = if options.configure_mcp {
      let config =
        provider.mcp_config(&root, options.global, &providers::ConfigContext::local())?;
      Some(ConfigEdit::plan(
        config,
        options.mcp_url.as_deref().unwrap_or(DEFAULT_MCP_URL),
      )?)
    } else {
      None
    };
    Ok(Self { skill_target, mcp })
  }

  pub fn describe(&self) {
    println!("Skills: {}", self.skill_target.display());
    if let Some(edit) = &self.mcp {
      println!("MCP:    {} (stove -> {})", edit.path.display(), edit.url);
    }
  }

  pub fn write_mcp_config(&self) -> Result<()> {
    if let Some(edit) = &self.mcp {
      edit.write().context(
        "skills installed, but MCP configuration failed; rerun the same command to finish setup",
      )?;
      println!(
        "MCP configured. Start Stove separately; endpoint: {}",
        edit.url
      );
    }
    Ok(())
  }
}

fn install_root(options: &SkillInstallOptions, cwd: &Path, home: Option<&Path>) -> Result<PathBuf> {
  if options.global {
    return home
      .map(Path::to_path_buf)
      .context("cannot determine home directory for --global");
  }
  if let Some(path) = &options.path {
    return Ok(cwd.join(path));
  }
  if options.force {
    return Ok(cwd.to_path_buf());
  }
  crate::skills::find_git_root(cwd).context(
    "stove skills install must be run inside a git repository (use --path . or --force for the current directory, or --global for all projects)",
  )
}
