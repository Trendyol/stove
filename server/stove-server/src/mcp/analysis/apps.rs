//! `stove_apps` tool — lists known apps with their latest run and a running
//! failure count.

use serde_json::Value;
use serde_json::json;

use super::Analyzer;
use super::common::display_error;
use super::common::fallback_message;
use super::common::tool_args;
use super::common::tool_call;
use crate::mcp::args::ListArgs;
use crate::mcp::args::parse;
use crate::mcp::contract::ArgName;
use crate::mcp::contract::ToolName;

impl Analyzer {
  pub(super) fn apps(&self, arguments: Value) -> Result<Value, String> {
    let args: ListArgs = parse(arguments)?;
    let limit = args.limit();
    let apps = self.repository.get_apps().map_err(display_error)?;
    let total_apps = apps.len();
    let apps: Vec<_> = apps.into_iter().take(limit).collect();
    let app_names: Vec<_> = apps.iter().map(|app| app.app_name.clone()).collect();
    let failed_runs_by_app = self
      .repository
      .failed_run_counts(&app_names)
      .map_err(display_error)?;

    let items: Vec<Value> = apps
      .into_iter()
      .map(|app| {
        json!({
          "app_name": app.app_name,
          "latest_run_id": app.latest_run_id,
          "latest_run_started_at": app.latest_run_started_at,
          "latest_status": app.latest_status,
          "stove_version": app.stove_version,
          "metadata": app.metadata,
          "failed_runs": failed_runs_by_app.get(&app.app_name).copied().unwrap_or_default(),
          "runs_tool_call": tool_call(ToolName::Runs, tool_args([(ArgName::AppName, json!(&app.app_name))])),
          "failures_tool_call": tool_call(ToolName::Failures, tool_args([(ArgName::AppName, json!(&app.app_name))])),
        })
      })
      .collect();

    let structured = json!({
      "apps": items,
      "count": items.len(),
      "total_apps": total_apps,
      "omitted_apps": total_apps.saturating_sub(items.len()),
      "fallback": fallback_message(),
    });
    Ok(structured)
  }
}
