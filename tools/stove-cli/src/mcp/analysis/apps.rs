//! `stove_apps` tool — lists known apps with their latest run and a running
//! failure count.

use std::collections::HashMap;

use serde_json::Value;
use serde_json::json;

use super::Analyzer;
use super::ToolOutput;
use super::common::display_error;
use super::common::fallback_message;
use super::common::output;
use super::common::tool_args;
use super::common::tool_call;
use crate::mcp::args::ListArgs;
use crate::mcp::args::parse;
use crate::mcp::contract::ArgName;
use crate::mcp::contract::ToolName;
use crate::storage::models::RunStatus;

impl Analyzer {
  pub(super) fn apps(&self, arguments: Value) -> Result<ToolOutput, String> {
    let args: ListArgs = parse(arguments)?;
    let limit = args.limit();
    let apps = self.repository.get_apps().map_err(display_error)?;
    let total_apps = apps.len();
    let runs = self.repository.get_runs(None).map_err(display_error)?;
    let mut failed_runs_by_app: HashMap<String, usize> = HashMap::new();
    for run in &runs {
      if run.status == RunStatus::Failed {
        *failed_runs_by_app.entry(run.app_name.clone()).or_insert(0) += 1;
      }
    }

    let items: Vec<Value> = apps
      .into_iter()
      .take(limit)
      .map(|app| {
        json!({
          "app_name": app.app_name,
          "latest_run_id": app.latest_run_id,
          "latest_status": app.latest_status,
          "stove_version": app.stove_version,
          "total_runs": app.total_runs,
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
    Ok(output(structured, "Known Stove apps"))
  }
}
