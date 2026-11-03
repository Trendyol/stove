//! `stove_failures` and `stove_failure_detail` tools — group failed tests by
//! app/run and surface the rich diagnostic bundle for a single failure.

use serde_json::Value;
use serde_json::json;

use super::Analyzer;
use super::common::display_error;
use super::common::exact_test_tool_call;
use super::common::failure_item;
use super::common::fallback_message;
use super::common::groups_have_running_runs;
use super::common::is_failed_status;
use super::common::selector_rules;
use super::common::test_json;
use super::common::timeline_summary;
use super::common::tool_call;
use super::evidence::clip_opt;
use super::evidence::entry_preview;
use super::evidence::interaction_preview;
use super::evidence::snapshot_summary;
use super::evidence::warning_preview;
use super::test_evidence::TestEvidence;
use super::trace_summary::{TraceDetail, summarize};
use crate::mcp::args::Budget;
use crate::mcp::args::ExactTestArgs;
use crate::mcp::args::FailuresArgs;
use crate::mcp::args::parse;
use crate::mcp::contract::ToolName;
use crate::storage::models::Entry;
use crate::storage::models::RunStatus;
use crate::storage::models::TestStatus;
use crate::storage::repository::FailureFilter;

impl Analyzer {
  pub(super) fn failures(&self, arguments: Value) -> Result<Value, String> {
    let args: FailuresArgs = parse(arguments.clone())?;
    let budget = Budget::from_args(args.common.budget.as_deref(), args.common.max_chars);
    let filter = FailureFilter {
      app: args.app_name,
      run_id: args.run_id,
    };
    let page = self
      .repository
      .get_failure_page(&filter, args.common.limit(), args.cursor.as_deref())
      .map_err(display_error)?;
    let total_failures = self
      .repository
      .count_failures(&filter)
      .map_err(display_error)?;
    let selected_failures = page.failures.len();
    let mut groups: Vec<Value> = Vec::new();
    for (run, test) in &page.failures {
      let failure = failure_item(run, test, budget.string_chars);
      if let Some(group) = groups.last_mut().filter(|group| group["run_id"] == run.id) {
        group["failures"]
          .as_array_mut()
          .expect("failure group array")
          .push(failure);
      } else {
        groups.push(json!({
          "app_name": run.app_name, "run_id": run.id, "run_status": run.status,
          "started_at": run.started_at, "ended_at": run.ended_at,
          "stove_version": run.stove_version, "failures": [failure],
        }));
      }
    }
    let next_tool_call = page.next_cursor.as_ref().map(|cursor| {
      let mut next = arguments;
      next["cursor"] = json!(cursor);
      tool_call(ToolName::Failures, next)
    });
    Ok(json!({
      "groups": groups,
      "failure_count": selected_failures,
      "total_failure_count": total_failures,
      "omitted_failures": total_failures.saturating_sub(i64::try_from(selected_failures).expect("bounded page")).max(0),
      "next_cursor": page.next_cursor,
      "next_tool_call": next_tool_call,
      "pagination": "Follow next_tool_call until null. Newest runs first, tests chronological within each run. Counts describe all matching retained failures; omitted_failures counts failures outside this page. Pages are not a database snapshot. Repeat from the first page after active runs complete.",
      "data_freshness": if groups_have_running_runs(&groups) { "partial" } else { "complete_or_idle" },
      "selector_rules": selector_rules(),
      "fallback": fallback_message(),
    }))
  }

  pub(super) fn failure_detail(&self, arguments: Value) -> Result<Value, String> {
    let args: ExactTestArgs = parse(arguments)?;
    let budget = Budget::from_args(args.common.budget.as_deref(), args.common.max_chars);
    let (run, test) = self.resolve_test(&args.run_id, &args.test_id)?;
    let TestEvidence {
      entries,
      snapshots,
      spans,
      interactions,
      warnings,
    } = TestEvidence::load(&self.repository, &test)?;

    let failed_entries: Vec<&Entry> = entries
      .iter()
      .filter(|entry| is_failed_status(&entry.result))
      .collect();
    let timeline_summary = timeline_summary(
      &entries,
      &args.run_id,
      &args.test_id,
      budget.timeline_events,
      budget.string_chars,
    );
    let trace_summary = summarize(
      &spans,
      &entries,
      &args.run_id,
      &args.test_id,
      budget,
      TraceDetail::Summary,
    );
    let snapshot_summaries: Vec<Value> = snapshots
      .iter()
      .take(budget.snapshots)
      .map(|snapshot| snapshot_summary(snapshot, budget.string_chars))
      .collect();
    // Unmatched mock exchanges carry the near-miss diagnoses — for "why did nothing
    // match" failures this IS the answer, so it belongs in the compact packet.
    let unmatched_interactions: Vec<Value> = interactions
      .iter()
      .filter(|interaction| !interaction.matched)
      .take(budget.interactions)
      .map(|interaction| interaction_preview(interaction, budget.string_chars))
      .collect();
    let unmatched_total = interactions
      .iter()
      .filter(|interaction| !interaction.matched)
      .count();
    let mock_warnings: Vec<Value> = warnings
      .iter()
      .take(budget.interactions)
      .map(|warning| warning_preview(warning, budget.string_chars))
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
        "spans": trace_summary["omitted_spans"],
        "snapshots": snapshots.len().saturating_sub(snapshot_summaries.len()),
        "unmatched_interactions": unmatched_total.saturating_sub(unmatched_interactions.len()),
        "mock_warnings": warnings.len().saturating_sub(mock_warnings.len()),
      },
      "timeline_summary": timeline_summary,
      "trace_summary": trace_summary,
      "snapshot_summaries": snapshot_summaries,
      "unmatched_interactions": unmatched_interactions,
      "mock_warnings": mock_warnings,
      "total_interactions": interactions.len(),
      "timeline_tool_call": exact_test_tool_call(ToolName::Timeline, &args.run_id, &args.test_id),
      "trace_tool_call": exact_test_tool_call(ToolName::Trace, &args.run_id, &args.test_id),
      "snapshot_tool_call": exact_test_tool_call(ToolName::Snapshot, &args.run_id, &args.test_id),
      "interactions_tool_call": exact_test_tool_call(ToolName::Interactions, &args.run_id, &args.test_id),
      "fallback": fallback_message(),
    });
    Ok(structured)
  }
}
