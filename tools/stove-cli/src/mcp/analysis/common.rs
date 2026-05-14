//! Helpers shared by every MCP analysis tool.
//!
//! Lives here so each per-tool module (apps, runs, failures, …) can import
//! only the small set it needs without dragging the others in.

use std::collections::BTreeMap;
use std::collections::BTreeSet;
use std::collections::HashMap;
use std::collections::HashSet;

use serde_json::Map;
use serde_json::Value;
use serde_json::json;

use super::evidence::clip_opt;
use super::evidence::log_preview;
use super::evidence::span_preview;
use crate::mcp::contract::ArgName;
use crate::mcp::contract::RunStatusValue;
use crate::mcp::contract::STATUS_ERROR;
use crate::mcp::contract::ToolName;
use crate::storage::models::Entry;
use crate::storage::models::LogRecord;
use crate::storage::models::Run;
use crate::storage::models::RunStatus;
use crate::storage::models::Span;
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

pub(super) fn failure_item(run: &Run, test: &Test) -> Value {
  json!({
    "app_name": run.app_name,
    "run_id": run.id,
    "test_id": test.id,
    "spec_name": test.spec_name,
    "test_path": test.test_path,
    "test_name": test.test_name,
    "status": test.status,
    "duration_ms": test.duration_ms,
    "error_summary": clip_opt(test.error.as_deref(), 600),
    "detail_tool_call": exact_test_tool_call(ToolName::FailureDetail, &run.id, &test.id),
    "timeline_tool_call": exact_test_tool_call(ToolName::Timeline, &run.id, &test.id),
    "trace_tool_call": exact_test_tool_call(ToolName::Trace, &run.id, &test.id),
  })
}

pub(super) fn test_json(test: &Test) -> Value {
  json!({
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
) -> Value {
  let selected = failure_window(entries, max_events);
  json!({
    "total_events": entries.len(),
    "failed_entries": entries.iter().filter(|entry| is_failed_status(&entry.result)).count(),
    "events": selected.iter().map(|entry| compact_event(entry)).collect::<Vec<_>>(),
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

pub(super) fn trace_summary(
  spans: &[Span],
  entries: &[Entry],
  run_id: &str,
  test_id: &str,
  max_spans: usize,
) -> Value {
  if spans.is_empty() {
    return json!({
      "trace_status": "uncorrelated",
      "trace_ids": trace_ids_from_entries(entries),
      "failed_spans": 0,
      "exception_spans": 0,
      "message": "No spans were correlated to this test. Fall back to timeline entries and logs if trace evidence is needed.",
    });
  }

  let ranked_trace_ids = ranked_trace_ids(spans, entries);
  let failed_spans: Vec<&Span> = spans.iter().filter(|span| is_failed_span(span)).collect();
  let exception_spans: Vec<&Span> = spans
    .iter()
    .filter(|span| span.exception_type.is_some())
    .collect();
  let primary_trace_id = ranked_trace_ids.first().cloned();
  let critical_path = primary_trace_id.map_or_else(Vec::new, |trace_id| {
    critical_path_for_trace(spans, &trace_id, max_spans)
  });

  json!({
    "trace_status": "correlated",
    "trace_ids": ranked_trace_ids,
    "total_spans": spans.len(),
    "omitted_spans": spans.len().saturating_sub(max_spans),
    "failed_spans": failed_spans.len(),
    "exception_spans": exception_spans.len(),
    "critical_path": critical_path,
    "exceptions": exception_spans
      .iter()
      .take(max_spans)
      .map(|span| span_preview(span, 600))
      .collect::<Vec<_>>(),
    "trace_tool_call": exact_test_tool_call(ToolName::Trace, run_id, test_id),
  })
}

fn trace_ids_from_entries(entries: &[Entry]) -> Vec<String> {
  entries
    .iter()
    .filter_map(|entry| entry.trace_id.clone())
    .filter(|trace_id| !trace_id.is_empty())
    .collect::<BTreeSet<_>>()
    .into_iter()
    .collect()
}

fn ranked_trace_ids(spans: &[Span], entries: &[Entry]) -> Vec<String> {
  let failed_entry_traces: HashSet<String> = entries
    .iter()
    .filter(|entry| is_failed_status(&entry.result))
    .filter_map(|entry| entry.trace_id.clone())
    .filter(|trace_id| !trace_id.is_empty())
    .collect();

  let mut scores: BTreeMap<String, i32> = BTreeMap::new();
  for span in spans {
    let mut score = 1;
    if is_failed_span(span) {
      score += 10;
    }
    if failed_entry_traces.contains(&span.trace_id) {
      score += 20;
    }
    *scores.entry(span.trace_id.clone()).or_insert(0) += score;
  }

  let mut ranked: Vec<(String, i32)> = scores.into_iter().collect();
  ranked.sort_by(|left, right| right.1.cmp(&left.1).then_with(|| left.0.cmp(&right.0)));
  ranked.into_iter().map(|(trace_id, _)| trace_id).collect()
}

fn critical_path_for_trace(spans: &[Span], trace_id: &str, max_spans: usize) -> Vec<Value> {
  let trace_spans: Vec<&Span> = spans
    .iter()
    .filter(|span| span.trace_id == trace_id)
    .collect();
  let target = trace_spans
    .iter()
    .find(|span| is_failed_span(span))
    .or_else(|| {
      trace_spans
        .iter()
        .max_by_key(|span| span.end_time_nanos - span.start_time_nanos)
    });

  let Some(target) = target else {
    return Vec::new();
  };

  let by_span_id: HashMap<&str, &Span> = trace_spans
    .iter()
    .map(|span| (span.span_id.as_str(), *span))
    .collect();
  let mut path = Vec::new();
  let mut current = Some(*target);
  let mut seen = HashSet::new();
  while let Some(span) = current {
    if !seen.insert(span.span_id.clone()) {
      break;
    }
    path.push(span_preview(span, 240));
    current = span
      .parent_span_id
      .as_deref()
      .and_then(|parent_id| by_span_id.get(parent_id).copied());
  }
  path.reverse();
  path.into_iter().take(max_spans).collect()
}

fn compact_event(entry: &Entry) -> Value {
  json!({
    "id": entry.id,
    "timestamp": entry.timestamp,
    "system": entry.system,
    "action": entry.action,
    "result": entry.result,
    "trace_id": entry.trace_id,
  })
}

pub(super) fn log_summary(
  logs: &[LogRecord],
  run_id: &str,
  test_id: &str,
  max_logs: usize,
  max_chars: usize,
) -> Value {
  let mut level_counts: BTreeMap<String, usize> = BTreeMap::new();
  let mut logger_counts: BTreeMap<String, usize> = BTreeMap::new();
  let mut dropped_logs = 0_usize;
  for log in logs {
    *level_counts.entry(log.severity_text.clone()).or_insert(0) += 1;
    *logger_counts.entry(log.logger.clone()).or_insert(0) += 1;
    if log.correlation_source == "DROPPED_MARKER" {
      dropped_logs += 1;
    }
  }

  let mut noisy_loggers: Vec<(String, usize)> = logger_counts.into_iter().collect();
  noisy_loggers.sort_by(|left, right| right.1.cmp(&left.1).then_with(|| left.0.cmp(&right.0)));

  let warn_or_higher: Vec<&LogRecord> = logs
    .iter()
    .filter(|log| log.severity_number >= 13)
    .collect();
  let selected_logs = if max_logs == 0 {
    Vec::new()
  } else {
    warn_or_higher
      .iter()
      .rev()
      .take(max_logs)
      .copied()
      .collect::<Vec<_>>()
      .into_iter()
      .rev()
      .collect()
  };

  json!({
    "total_logs": logs.len(),
    "warn_or_error_logs": warn_or_higher.len(),
    "dropped_logs": dropped_logs,
    "levels": level_counts,
    "noisy_loggers": noisy_loggers
      .into_iter()
      .take(5)
      .map(|(logger, count)| json!({ "logger": logger, "count": count }))
      .collect::<Vec<_>>(),
    "last_warn_or_error_logs": selected_logs
      .iter()
      .map(|log| log_preview(log, max_chars))
      .collect::<Vec<_>>(),
    "logs_tool_call": exact_test_tool_call(ToolName::Logs, run_id, test_id),
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
        .get_entries(&run.id, &test.id)
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

pub(super) fn is_failed_span(span: &Span) -> bool {
  span.status.eq_ignore_ascii_case(STATUS_ERROR)
    || span
      .status
      .eq_ignore_ascii_case(RunStatusValue::Failed.as_str())
    || span.exception_type.is_some()
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

pub(super) fn output(structured: Value, heading: &str) -> super::ToolOutput {
  let body = compact_text(&structured);
  let text = format!("{heading}\n{body}");
  super::ToolOutput { structured, text }
}

fn compact_text(value: &Value) -> String {
  serde_json::to_string_pretty(value)
    .unwrap_or_else(|_| "Stove MCP result could not be rendered as JSON".to_string())
}

pub(super) fn display_error(error: impl std::fmt::Display) -> String {
  error.to_string()
}
