//! Helpers shared by every MCP analysis tool.
//!
//! Lives here so each per-tool module (apps, runs, failures, …) can import
//! only the small set it needs without dragging the others in.

use crate::navigation::{error_reference, reference};
use std::collections::BTreeSet;

use serde_json::Map;
use serde_json::Value;
use serde_json::json;

use super::evidence::{clip_opt, clip_string};
use crate::mcp::contract::ArgName;
use crate::mcp::contract::RunStatusValue;
use crate::mcp::contract::ToolName;
use crate::storage::models::Entry;
use crate::storage::models::Run;
use crate::storage::models::RunStatus;
use crate::storage::models::Test;
use crate::storage::models::TestStatus;
use crate::storage::repository::Repository;

pub(super) fn selected_runs(
  repository: &Repository,
  app_name: Option<&str>,
  run_id: Option<&str>,
) -> Result<Vec<Run>, String> {
  if let Some(run_id) = run_id {
    return repository
      .get_run(run_id)
      .map_err(display_error)?
      .map_or_else(|| Ok(Vec::new()), |run| Ok(vec![run]));
  }

  let mut runs = repository.get_runs(app_name).map_err(display_error)?;
  runs.retain(|run| run.status == RunStatus::Failed || run.status == RunStatus::Running);
  Ok(runs)
}

pub(super) fn failure_item(run: &Run, test: &Test, max_chars: usize) -> Value {
  json!({
    "navigation": reference(&run.id, Some(&test.id), None),
    "error_navigation": error_reference(&run.id, &test.id, test.error.as_deref()),
    "app_name": run.app_name,
    "run_id": run.id,
    "test_id": test.id,
    "spec_name": test.spec_name,
    "test_path": test.test_path,
    "test_name": test.test_name,
    "status": test.status,
    "duration_ms": test.duration_ms,
    "error_summary": clip_opt(test.error.as_deref(), max_chars),
    "detail_tool_call": exact_test_tool_call(ToolName::FailureDetail, &run.id, &test.id),
    "timeline_tool_call": exact_test_tool_call(ToolName::Timeline, &run.id, &test.id),
    "trace_tool_call": exact_test_tool_call(ToolName::Trace, &run.id, &test.id),
  })
}

pub(super) fn test_json(test: &Test) -> Value {
  json!({
    "navigation": reference(&test.run_id, Some(&test.id), None),
    "error_navigation": error_reference(&test.run_id, &test.id, test.error.as_deref()),
    "test_id": test.id,
    "test_name": test.test_name,
    "spec_name": test.spec_name,
    "test_path": test.test_path,
    "status": test.status,
    "started_at": test.started_at,
    "ended_at": test.ended_at,
    "duration_ms": test.duration_ms,
  })
}

pub(super) fn timeline_summary(
  entries: &[Entry],
  run_id: &str,
  test_id: &str,
  max_events: usize,
  max_chars: usize,
) -> Value {
  let selected = failure_window(entries, max_events);
  json!({
    "total_events": entries.len(),
    "event_scope": "report_entries",
    "omitted_events": entries.len().saturating_sub(selected.len()),
    "failed_entries": entries.iter().filter(|entry| is_failed_status(&entry.result)).count(),
    "events": selected.iter().map(|entry| compact_event(entry, max_chars)).collect::<Vec<_>>(),
    "timeline_tool_call": exact_test_tool_call(ToolName::Timeline, run_id, test_id),
  })
}

pub(super) fn failure_window(entries: &[Entry], max_events: usize) -> Vec<&Entry> {
  if entries.is_empty() || max_events == 0 {
    return Vec::new();
  }
  let failed_indexes: Vec<usize> = entries
    .iter()
    .enumerate()
    .filter_map(|(index, entry)| is_failed_status(&entry.result).then_some(index))
    .collect();

  if failed_indexes.is_empty() {
    return entries.iter().take(max_events).collect();
  }

  let mut selected = BTreeSet::new();
  for index in failed_indexes {
    let start = index.saturating_sub(2);
    let end = (index + 2).min(entries.len().saturating_sub(1));
    for selected_index in start..=end {
      selected.insert(selected_index);
    }
  }

  selected
    .into_iter()
    .take(max_events)
    .filter_map(|index| entries.get(index))
    .collect()
}

fn compact_event(entry: &Entry, max_chars: usize) -> Value {
  json!({
    "navigation": reference(&entry.run_id, Some(&entry.test_id), Some(("entry", entry.id))),
    "id": entry.id,
    "timestamp": entry.timestamp,
    "system": clip_string(&entry.system, max_chars),
    "action": clip_string(&entry.action, max_chars),
    "result": entry.result,
    "trace_id": entry.trace_id,
  })
}

pub(super) fn groups_have_running_runs(groups: &[Value]) -> bool {
  groups.iter().any(|group| {
    group.get("run_status").and_then(Value::as_str) == Some(RunStatusValue::Running.as_str())
  })
}

pub(super) fn correlated_test_for_trace(
  repository: &Repository,
  run: &Run,
  trace_id: &str,
) -> Option<Test> {
  repository
    .get_tests_for_run(&run.id)
    .ok()?
    .into_iter()
    .find(|test| {
      repository
        .get_raw_entries(&run.id, &test.id)
        .is_ok_and(|entries| {
          entries
            .iter()
            .any(|entry| entry.trace_id.as_deref() == Some(trace_id))
        })
    })
}

pub(super) fn is_failed_test(test: &Test) -> bool {
  matches!(test.status, TestStatus::Failed | TestStatus::Error)
}

pub(super) fn is_failed_status(status: &TestStatus) -> bool {
  matches!(status, TestStatus::Failed | TestStatus::Error)
}

pub(super) fn tool_call(tool: ToolName, arguments: Value) -> Value {
  let mut call = Map::new();
  call.insert("tool".to_string(), Value::String(tool.as_str().to_string()));
  call.insert("arguments".to_string(), arguments);
  Value::Object(call)
}

pub(super) fn exact_test_tool_call(tool: ToolName, run_id: &str, test_id: &str) -> Value {
  tool_call(
    tool,
    tool_args([
      (ArgName::RunId, json!(run_id)),
      (ArgName::TestId, json!(test_id)),
    ]),
  )
}

pub(super) fn tool_args(entries: impl IntoIterator<Item = (ArgName, Value)>) -> Value {
  Value::Object(
    entries
      .into_iter()
      .map(|(key, value)| (key.as_str().to_string(), value))
      .collect(),
  )
}

pub(super) fn selector_rules() -> Value {
  json!({
    "app_name": "grouping/filter only; multiple runs may exist per app",
    "run_id": "canonical execution boundary",
    "test_id": "unique only within run_id",
    "exact_test_selector": [ArgName::RunId.as_str(), ArgName::TestId.as_str()],
  })
}

pub(super) fn fallback_message() -> &'static str {
  "If Stove MCP is unavailable, incomplete, or ambiguous, fall back to normal test output, Stove failure reports, and logs."
}

pub(super) fn display_error(error: impl std::fmt::Display) -> String {
  error.to_string()
}
