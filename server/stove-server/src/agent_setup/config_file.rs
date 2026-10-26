//! Plan and persist configuration changes without knowing agent conventions.

use std::fs;
use std::io::Write;
use std::path::{Path, PathBuf};

use anyhow::{Context, Result, bail};

use super::providers::McpConfig;

pub(super) struct ConfigEdit {
  pub path: PathBuf,
  original: Option<String>,
  updated: String,
  pub url: String,
}

impl ConfigEdit {
  pub(super) fn plan(config: McpConfig, url: &str) -> Result<Self> {
    let McpConfig { path, format } = config;
    let endpoint = reqwest::Url::parse(url).context("invalid MCP URL")?;
    if !matches!(endpoint.scheme(), "http" | "https") || endpoint.host_str().is_none() {
      bail!("MCP URL must be an absolute http:// or https:// endpoint");
    }
    if path.is_symlink() {
      bail!(
        "MCP config is a symlink; configure its target directly: {}",
        path.display()
      );
    }
    let original = read_optional(&path)?;
    let updated = format.merge(original.as_deref(), url).with_context(|| {
      format!(
        "cannot update {}; existing configuration was not changed",
        path.display()
      )
    })?;
    Ok(Self {
      path,
      original,
      updated,
      url: url.to_string(),
    })
  }

  pub(super) fn write(&self) -> Result<()> {
    if self.original.as_deref() == Some(&self.updated) {
      return Ok(());
    }
    if self.path.is_symlink() || read_optional(&self.path)? != self.original {
      bail!(
        "{} changed during setup; rerun to merge the latest configuration",
        self.path.display()
      );
    }
    let parent = self
      .path
      .parent()
      .context("configuration path has no parent")?;
    fs::create_dir_all(parent)?;
    let temporary = parent.join(format!(".stove-config-{}", uuid::Uuid::new_v4()));
    let result = self
      .write_temporary(&temporary)
      .and_then(|()| fs::rename(&temporary, &self.path).map_err(Into::into));
    if result.is_err() {
      let _ = fs::remove_file(temporary);
    }
    result
  }

  fn write_temporary(&self, path: &Path) -> Result<()> {
    let mut options = fs::OpenOptions::new();
    options.write(true).create_new(true);
    #[cfg(unix)]
    {
      use std::os::unix::fs::OpenOptionsExt;
      options.mode(0o600);
    }
    let mut file = options.open(path)?;
    if self.original.is_some() {
      file.set_permissions(fs::metadata(&self.path)?.permissions())?;
    }
    file.write_all(self.updated.as_bytes())?;
    file.sync_all()?;
    Ok(())
  }
}

fn read_optional(path: &Path) -> Result<Option<String>> {
  match fs::read_to_string(path) {
    Ok(text) => Ok(Some(text)),
    Err(error) if error.kind() == std::io::ErrorKind::NotFound => Ok(None),
    Err(error) => Err(error).with_context(|| format!("cannot read {}", path.display())),
  }
}

#[cfg(test)]
mod tests {
  use super::super::DEFAULT_MCP_URL;
  use super::super::formats::{JsonFormat, McpFormat};
  use super::*;

  #[test]
  fn configuration_changed_during_setup_is_not_overwritten() {
    let directory = tempfile::TempDir::new().unwrap();
    let path = directory.path().join(".mcp.json");
    fs::write(&path, "{}").unwrap();
    let edit = ConfigEdit::plan(
      McpConfig {
        path: path.clone(),
        format: McpFormat::Json(JsonFormat::HTTP),
      },
      DEFAULT_MCP_URL,
    )
    .unwrap();
    fs::write(&path, "{\"new_setting\":true}").unwrap();
    assert!(edit.write().is_err());
    assert_eq!(fs::read_to_string(path).unwrap(), "{\"new_setting\":true}");
  }
}
