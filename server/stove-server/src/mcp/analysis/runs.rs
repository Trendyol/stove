//! `stove_runs` tool — cursor-paginated listing with optional filters.

use serde_json::Value;
use serde_json::json;

use super::Analyzer;
use super::common::display_error;
use super::common::fallback_message;
use super::common::selector_rules;
use super::common::tool_args;
use super::common::tool_call;
use crate::mcp::args::RunsArgs;
use crate::mcp::args::parse;
use crate::mcp::contract::ArgName;
use crate::mcp::contract::ToolName;

impl Analyzer {
  pub(super) fn runs(&self, arguments: Value) -> Result<Value, String> {
    let args: RunsArgs = parse(arguments.clone())?;
    let limit = args.common.limit();
    let filter = crate::storage::repository::RunFilter {
      app: args.app_name.clone(),
      status: args
        .status
        .as_deref()
        .map(|status| status.to_ascii_uppercase().parse())
        .transpose()?,
      metadata: args.metadata.clone().unwrap_or_default(),
    };
    let page = self
      .repository
      .get_run_page(&filter, limit, args.cursor.as_deref())
      .map_err(display_error)?;
    let next_cursor = page.next_cursor;
    let next_tool_call = next_cursor.as_ref().map(|cursor| {
      let mut next_arguments = arguments;
      next_arguments[ArgName::Cursor.as_str()] = json!(cursor);
      tool_call(ToolName::Runs, next_arguments)
    });
    let runs = page.runs;

    let items: Vec<Value> = runs
      .into_iter()
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
          "metadata": run.metadata,
          "failures_tool_call": tool_call(ToolName::Failures, tool_args([(ArgName::RunId, json!(&run.id))])),
        })
      })
      .collect();

    let structured = json!({
      "runs": items,
      "count": items.len(),
      "next_cursor": next_cursor,
      "next_tool_call": next_tool_call,
      "pagination": "Pass next_cursor as cursor with the same filters until null. Pages are not a database snapshot.",
      "selector_rules": selector_rules(),
      "fallback": fallback_message(),
    });
    Ok(structured)
  }
}
