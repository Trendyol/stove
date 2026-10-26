//! Comment-preserving MCP formats, independent of installation destinations.

use anyhow::{Context, Result, ensure};
use jsonc_parser::cst::{CstInputValue, CstObject, CstRootNode};
use toml_edit::{DocumentMut, Item, Table};

const LOCAL_PROCESS_CONFLICT: &str =
  "stove is already configured as a local process; remove that entry before configuring HTTP MCP";

pub(super) enum McpFormat {
  Toml,
  Json(JsonFormat),
}

impl McpFormat {
  pub fn merge(&self, original: Option<&str>, url: &str) -> Result<String> {
    match self {
      Self::Toml => merge_toml(original.unwrap_or(""), url),
      Self::Json(format) => merge_json(original.unwrap_or("{}\n"), *format, url),
    }
  }
}

#[derive(Clone, Copy)]
pub(super) struct JsonFormat {
  pub servers_key: &'static str,
  pub transport: &'static str,
  pub enable_connection: bool,
}

impl JsonFormat {
  pub const HTTP: Self = Self {
    servers_key: "mcpServers",
    transport: "http",
    enable_connection: false,
  };
  pub const REMOTE: Self = Self {
    servers_key: "mcp",
    transport: "remote",
    enable_connection: true,
  };
}

fn merge_toml(text: &str, url: &str) -> Result<String> {
  let mut document = text.parse::<DocumentMut>()?;
  let servers = document
    .entry("mcp_servers")
    .or_insert(Item::Table(Table::new()))
    .as_table_like_mut()
    .context("mcp_servers must be a table")?;
  let stove = servers
    .entry("stove")
    .or_insert(Item::Table(Table::new()))
    .as_table_like_mut()
    .context("mcp_servers.stove must be a table")?;
  ensure!(!stove.contains_key("command"), LOCAL_PROCESS_CONFLICT);
  stove.insert("url", toml_edit::value(url));
  stove.insert("enabled", toml_edit::value(true));
  Ok(document.to_string())
}

fn merge_json(text: &str, format: JsonFormat, url: &str) -> Result<String> {
  let root = CstRootNode::parse(text, &jsonc_parser::ParseOptions::default())?;
  let object = root
    .object_value_or_create()
    .context("configuration must be a JSON object")?;
  let key = format.servers_key;
  let servers = object
    .object_value_or_create(key)
    .with_context(|| format!("{key} must be an object"))?;
  let stove = servers
    .object_value_or_create("stove")
    .context("stove must be an object")?;
  ensure!(stove.get("command").is_none(), LOCAL_PROCESS_CONFLICT);
  set_json_property(&stove, "url", jsonc_parser::json!(url));
  set_json_property(&stove, "type", jsonc_parser::json!(format.transport));
  if format.enable_connection {
    set_json_property(&stove, "enabled", jsonc_parser::json!(true));
  }
  Ok(root.to_string())
}

fn set_json_property(object: &CstObject, name: &str, value: CstInputValue) {
  if let Some(property) = object.get(name) {
    property.set_value(value);
  } else {
    object.append(name, value);
  }
}

#[cfg(test)]
mod tests {
  use super::super::DEFAULT_MCP_URL;
  use super::*;

  #[test]
  fn json_connections_use_the_agents_transport_and_keep_headers() {
    for format in [JsonFormat::HTTP, JsonFormat::REMOTE] {
      let key = format.servers_key;
      let original = serde_json::json!({key: {"stove": {
        "type": "sse", "url": "http://old/mcp", "headers": {"X-Team": "checkout"}
      }}});
      let updated = merge_json(&original.to_string(), format, DEFAULT_MCP_URL).unwrap();
      let parsed: serde_json::Value = serde_json::from_str(&updated).unwrap();
      let stove = &parsed[key]["stove"];
      assert_eq!(stove["url"], DEFAULT_MCP_URL);
      assert_eq!(stove["headers"]["X-Team"], "checkout");
      assert_eq!(stove["type"], format.transport);
      if format.enable_connection {
        assert_eq!(stove["enabled"], true);
      }
    }
  }

  #[test]
  fn incompatible_existing_configs_are_rejected() {
    for original in [
      "[]",
      r#"{"mcpServers": false}"#,
      r#"{"mcpServers": {"stove": []}}"#,
      r#"{"mcpServers": {"stove": {"command": "custom-server"}}}"#,
    ] {
      assert!(merge_json(original, JsonFormat::HTTP, DEFAULT_MCP_URL).is_err());
    }
    assert!(
      merge_toml(
        "[mcp_servers.stove]\ncommand = 'custom-server'",
        DEFAULT_MCP_URL
      )
      .is_err()
    );
  }
}
