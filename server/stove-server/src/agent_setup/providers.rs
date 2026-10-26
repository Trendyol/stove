//! Agent adapters own skill discovery paths and MCP configuration conventions.
//! Register new adapters in `provider` and the CLI's `SkillAgent` enum.
//! Shared parsing and persistence stay in the sibling modules.

use std::path::{Path, PathBuf};

use anyhow::{Result, bail};

use super::formats::{JsonFormat, McpFormat};
use crate::config::SkillAgent;

/// Explicit environment prevents web previews from inspecting the server user's files.
#[derive(Default)]
pub(super) struct ConfigContext {
  pub codex_home: Option<PathBuf>,
  pub xdg_config_home: Option<PathBuf>,
  pub inspect_existing: bool,
}

impl ConfigContext {
  pub fn local() -> Self {
    Self {
      codex_home: std::env::var_os("CODEX_HOME").map(PathBuf::from),
      xdg_config_home: std::env::var_os("XDG_CONFIG_HOME").map(PathBuf::from),
      inspect_existing: true,
    }
  }
}

pub(super) struct McpConfig {
  pub path: PathBuf,
  pub format: McpFormat,
}

/// Provider selection is independent of downloads and filesystem mutations.
pub(super) trait AgentProvider {
  fn label(&self) -> &'static str;

  fn note(&self) -> &'static str {
    ""
  }

  fn install_link(&self, _endpoint: &str) -> Option<String> {
    None
  }

  fn skill_directory(&self) -> &'static str {
    ".agents/skills/stove"
  }

  fn mcp_config(&self, root: &Path, global: bool, context: &ConfigContext) -> Result<McpConfig>;
}

pub(super) fn provider(agent: SkillAgent) -> &'static dyn AgentProvider {
  match agent {
    SkillAgent::Universal => &Universal,
    SkillAgent::Codex => &Codex,
    SkillAgent::Claude => &Claude,
    SkillAgent::Cursor => &Cursor,
    SkillAgent::Opencode => &OpenCode,
  }
}

struct Universal;

impl AgentProvider for Universal {
  fn label(&self) -> &'static str {
    "Universal"
  }

  fn mcp_config(&self, _root: &Path, _global: bool, _context: &ConfigContext) -> Result<McpConfig> {
    bail!("--configure-mcp requires --agent codex, claude, cursor, or opencode")
  }
}

struct Codex;

impl AgentProvider for Codex {
  fn note(&self) -> &'static str {
    "Project configuration requires a trusted project. Global MCP configuration honors CODEX_HOME."
  }

  fn label(&self) -> &'static str {
    "Codex"
  }

  fn mcp_config(&self, root: &Path, global: bool, context: &ConfigContext) -> Result<McpConfig> {
    let directory = if global {
      context
        .codex_home
        .clone()
        .unwrap_or_else(|| root.join(".codex"))
    } else {
      root.join(".codex")
    };
    Ok(McpConfig {
      path: directory.join("config.toml"),
      format: McpFormat::Toml,
    })
  }
}

struct Claude;

impl AgentProvider for Claude {
  fn label(&self) -> &'static str {
    "Claude Code"
  }

  fn skill_directory(&self) -> &'static str {
    ".claude/skills/stove"
  }

  fn mcp_config(&self, root: &Path, global: bool, _context: &ConfigContext) -> Result<McpConfig> {
    Ok(McpConfig {
      path: root.join(if global { ".claude.json" } else { ".mcp.json" }),
      format: McpFormat::Json(JsonFormat::HTTP),
    })
  }
}

struct Cursor;

impl AgentProvider for Cursor {
  fn install_link(&self, endpoint: &str) -> Option<String> {
    use base64::Engine;
    let config = serde_json::json!({"type": "http", "url": endpoint});
    let encoded = base64::engine::general_purpose::STANDARD.encode(config.to_string());
    let mut link = reqwest::Url::parse("cursor://anysphere.cursor-deeplink/mcp/install").ok()?;
    link
      .query_pairs_mut()
      .append_pair("name", "stove")
      .append_pair("config", &encoded);
    Some(link.into())
  }

  fn label(&self) -> &'static str {
    "Cursor"
  }

  fn mcp_config(&self, root: &Path, _global: bool, _context: &ConfigContext) -> Result<McpConfig> {
    Ok(McpConfig {
      path: root.join(".cursor/mcp.json"),
      format: McpFormat::Json(JsonFormat::HTTP),
    })
  }
}

struct OpenCode;

impl AgentProvider for OpenCode {
  fn note(&self) -> &'static str {
    "Use your existing opencode.jsonc when present. Global MCP configuration honors XDG_CONFIG_HOME."
  }

  fn label(&self) -> &'static str {
    "OpenCode"
  }

  fn mcp_config(&self, root: &Path, global: bool, context: &ConfigContext) -> Result<McpConfig> {
    let directory = if global {
      context
        .xdg_config_home
        .clone()
        .unwrap_or_else(|| root.join(".config"))
        .join("opencode")
    } else {
      root.to_path_buf()
    };
    let jsonc = directory.join("opencode.jsonc");
    Ok(McpConfig {
      path: if context.inspect_existing && jsonc.exists() {
        jsonc
      } else {
        directory.join("opencode.json")
      },
      format: McpFormat::Json(JsonFormat::REMOTE),
    })
  }
}
