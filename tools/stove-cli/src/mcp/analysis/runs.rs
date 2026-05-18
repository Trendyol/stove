//! `stove_runs` tool — paginated listing of runs with optional app/status
//! filters.

use serde_json::Value;
use serde_json::json;

use super::Analyzer;
use super::ToolOutput;
use super::common::display_error;
use super::common::fallback_message;
use super::common::output;
use super::common::selector_rules;
use super::common::tool_args;
use super::common::tool_call;
use crate::mcp::args::RunsArgs;
use crate::mcp::args::parse;
use crate::mcp::contract::ArgName;
use crate::mcp::contract::ToolName;

impl Analyzer {
  pub(super) fn runs(&self, arguments: Value) -> Result<ToolOutput, String> {
    let args: RunsArgs = parse(arguments)?;
    let limit = args.common.limit();
    let status_filter = args.status.as_deref().map(str::to_ascii_uppercase);
    let mut runs = self
      .repository
      .get_runs(args.app_name.as_deref())
      .map_err(display_error)?;

    if let Some(status) = &status_filter {
      runs.retain(|run| run.status.to_string() == *status);
    }
    let total_runs = runs.len();

    let items: Vec<Value> = runs
      .into_iter()
      .take(limit)
      .map(|run| {
        json!({
          "app_name": run.app_name,
          "run_id": run.id,
          "status": run.status,
          "started_at": run.started_at,
          "ended_at": run.ended_at,
          "total_tests": run.total_tests,
          "passed": run.passed,
          "failed": run.failed,
          "duration_ms": run.duration_ms,
          "stove_version": run.stove_version,
          "systems": run.systems,
          "failures_tool_call": tool_call(ToolName::Failures, tool_args([(ArgName::RunId, json!(&run.id))])),
        })
      })
      .collect();

    let structured = json!({
      "runs": items,
      "count": items.len(),
      "total_runs": total_runs,
      "omitted_runs": total_runs.saturating_sub(items.len()),
      "selector_rules": selector_rules(),
      "fallback": fallback_message(),
    });
    Ok(output(structured, "Stove runs"))
  }
}
