//! `stove_failures` and `stove_failure_detail` tools — group failed tests by
//! app/run and surface the rich diagnostic bundle for a single failure.

use serde_json::Value;
use serde_json::json;

use super::Analyzer;
use super::ToolOutput;
use super::common::display_error;
use super::common::exact_test_tool_call;
use super::common::failure_item;
use super::common::fallback_message;
use super::common::groups_have_running_runs;
use super::common::is_failed_status;
use super::common::is_failed_test;
use super::common::log_summary;
use super::common::output;
use super::common::selected_runs;
use super::common::selector_rules;
use super::common::test_json;
use super::common::timeline_summary;
use super::common::trace_summary;
use super::evidence::clip_opt;
use super::evidence::entry_preview;
use super::evidence::snapshot_summary;
use crate::mcp::args::Budget;
use crate::mcp::args::ExactTestArgs;
use crate::mcp::args::FailuresArgs;
use crate::mcp::args::parse;
use crate::mcp::contract::ToolName;
use crate::storage::models::Entry;
use crate::storage::models::LogQuery;
use crate::storage::models::RunStatus;
use crate::storage::models::Test;
use crate::storage::models::TestStatus;

impl Analyzer {
  pub(super) fn failures(&self, arguments: Value) -> Result<ToolOutput, String> {
    let args: FailuresArgs = parse(arguments)?;
    let limit = args.common.limit();
    let runs = selected_runs(
      &self.repository,
      args.app_name.as_deref(),
      args.run_id.as_deref(),
    )?;

    let mut groups = Vec::new();
    let mut selected_failures = 0_usize;
    let mut total_failures = 0_usize;
    for run in runs {
      let tests = self
        .repository
        .get_tests_for_run(&run.id)
        .map_err(display_error)?;
      let failed_tests: Vec<Test> = tests.into_iter().filter(is_failed_test).collect();
      total_failures += failed_tests.len();

      let remaining = limit.saturating_sub(selected_failures);
      let failures: Vec<Value> = failed_tests
        .into_iter()
        .take(remaining)
        .map(|test| failure_item(&run, &test))
        .collect();

      if failures.is_empty() {
        continue;
      }
      selected_failures += failures.len();
      groups.push(json!({
        "app_name": run.app_name,
        "run_id": run.id,
        "run_status": run.status,
        "started_at": run.started_at,
        "ended_at": run.ended_at,
        "stove_version": run.stove_version,
        "failures": failures,
      }));
    }

    let structured = json!({
      "groups": groups,
      "failure_count": selected_failures,
      "total_failure_count": total_failures,
      "omitted_failures": total_failures.saturating_sub(selected_failures),
      "data_freshness": if groups_have_running_runs(&groups) { "partial" } else { "complete_or_idle" },
      "selector_rules": selector_rules(),
      "fallback": fallback_message(),
    });
    Ok(output(
      structured,
      "Stove failed tests grouped by app and run",
    ))
  }

  pub(super) fn failure_detail(&self, arguments: Value) -> Result<ToolOutput, String> {
    let args: ExactTestArgs = parse(arguments)?;
    let budget = Budget::from_args(args.common.budget.as_deref(), args.common.max_chars);
    let (run, test) = self.resolve_test(&args.run_id, &args.test_id)?;
    let entries = self
      .repository
      .get_entries(&args.run_id, &args.test_id)
      .map_err(display_error)?;
    let snapshots = self
      .repository
      .get_snapshots(&args.run_id, &args.test_id)
      .map_err(display_error)?;
    let spans = self
      .repository
      .get_spans_for_test(&args.run_id, &args.test_id)
      .map_err(display_error)?;
    let logs = self
      .repository
      .get_logs_for_test(
        &args.run_id,
        &args.test_id,
        &LogQuery {
          limit: budget.logs,
          ..LogQuery::default()
        },
      )
      .map_err(display_error)?;

    let failed_entries: Vec<&Entry> = entries
      .iter()
      .filter(|entry| is_failed_status(&entry.result))
      .collect();
    let timeline_summary = timeline_summary(
      &entries,
      &args.run_id,
      &args.test_id,
      budget.timeline_events,
    );
    let trace_summary = trace_summary(
      &spans,
      &entries,
      &args.run_id,
      &args.test_id,
      budget.trace_spans,
    );
    let snapshot_summaries: Vec<Value> = snapshots
      .iter()
      .take(budget.snapshots)
      .map(|snapshot| snapshot_summary(snapshot, budget.string_chars))
      .collect();

    let structured = json!({
      "app_name": run.app_name,
      "run_id": run.id,
      "run_status": run.status,
      "test": test_json(&test),
      "data_freshness": if test.status == TestStatus::Running || run.status == RunStatus::Running { "partial" } else { "complete_or_idle" },
      "error_summary": clip_opt(test.error.as_deref(), budget.string_chars),
      "failed_entries": failed_entries
        .iter()
        .take(budget.failed_entries)
        .map(|entry| entry_preview(entry, budget.string_chars))
        .collect::<Vec<_>>(),
      "omitted": {
        "entries": entries.len().saturating_sub(budget.timeline_events),
        "failed_entries": failed_entries.len().saturating_sub(budget.failed_entries),
        "spans": spans.len().saturating_sub(budget.trace_spans),
        "logs": logs.len().saturating_sub(budget.logs),
        "snapshots": snapshots.len().saturating_sub(snapshot_summaries.len()),
      },
      "timeline_summary": timeline_summary,
      "trace_summary": trace_summary,
      "log_summary": log_summary(&logs, &args.run_id, &args.test_id, budget.logs, budget.string_chars),
      "snapshot_summaries": snapshot_summaries,
      "timeline_tool_call": exact_test_tool_call(ToolName::Timeline, &args.run_id, &args.test_id),
      "trace_tool_call": exact_test_tool_call(ToolName::Trace, &args.run_id, &args.test_id),
      "logs_tool_call": exact_test_tool_call(ToolName::Logs, &args.run_id, &args.test_id),
      "snapshot_tool_call": exact_test_tool_call(ToolName::Snapshot, &args.run_id, &args.test_id),
      "fallback": fallback_message(),
    });
    Ok(output(structured, "Stove failure detail"))
  }
}
