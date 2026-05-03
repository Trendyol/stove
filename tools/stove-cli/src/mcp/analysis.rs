mod evidence;

use std::collections::{BTreeMap, BTreeSet, HashMap, HashSet};
use std::sync::Arc;
use std::time::Duration;

use serde_json::{Value, json};

use self::evidence::{clip_opt, entry_preview, snapshot_detail, snapshot_summary, span_preview};
use super::args::{
  Budget, ExactTestArgs, FailuresArgs, ListArgs, RawEvidenceArgs, RunsArgs, SnapshotArgs,
  TimelineArgs, TraceArgs, parse,
};
use super::contract::{
  ArgName, RawEvidenceKind, RunStatusValue, STATUS_ERROR, TimelineFocus, ToolName,
};
use crate::ingest::EventIngestor;
use crate::storage::models::{Entry, Run, RunStatus, Span, Test, TestStatus};
use crate::storage::repository::Repository;

const FLUSH_TIMEOUT: Duration = Duration::from_millis(500);

#[derive(Debug, Clone)]
pub struct ToolOutput {
  pub structured: Value,
  pub text: String,
}

#[derive(Clone)]
pub struct Analyzer {
  repository: Arc<Repository>,
  ingestor: Option<EventIngestor>,
}

impl Analyzer {
  #[must_use]
  pub fn new(repository: Arc<Repository>, ingestor: Option<EventIngestor>) -> Self {
    Self {
      repository,
      ingestor,
    }
  }

  pub async fn call_tool(&self, name: &str, arguments: Value) -> Result<ToolOutput, String> {
    self.flush_pending().await;
    match ToolName::from_str(name) {
      Some(ToolName::Apps) => self.apps(arguments),
      Some(ToolName::Runs) => self.runs(arguments),
      Some(ToolName::Failures) => self.failures(arguments),
      Some(ToolName::FailureDetail) => self.failure_detail(arguments),
      Some(ToolName::Timeline) => self.timeline(arguments),
      Some(ToolName::Trace) => self.trace(arguments),
      Some(ToolName::Snapshot) => self.snapshot(arguments),
      Some(ToolName::RawEvidence) => self.raw_evidence(arguments),
      None => Err(format!("unknown Stove MCP tool: {name}")),
    }
  }

  async fn flush_pending(&self) {
    let Some(ingestor) = &self.ingestor else {
      return;
    };

    let _ = tokio::time::timeout(FLUSH_TIMEOUT, ingestor.flush_pending()).await;
  }

  fn apps(&self, arguments: Value) -> Result<ToolOutput, String> {
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

  fn runs(&self, arguments: Value) -> Result<ToolOutput, String> {
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

  fn failures(&self, arguments: Value) -> Result<ToolOutput, String> {
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

  fn failure_detail(&self, arguments: Value) -> Result<ToolOutput, String> {
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
        "snapshots": snapshots.len().saturating_sub(snapshot_summaries.len()),
      },
      "timeline_summary": timeline_summary,
      "trace_summary": trace_summary,
      "snapshot_summaries": snapshot_summaries,
      "timeline_tool_call": exact_test_tool_call(ToolName::Timeline, &args.run_id, &args.test_id),
      "trace_tool_call": exact_test_tool_call(ToolName::Trace, &args.run_id, &args.test_id),
      "snapshot_tool_call": exact_test_tool_call(ToolName::Snapshot, &args.run_id, &args.test_id),
      "fallback": fallback_message(),
    });
    Ok(output(structured, "Stove failure detail"))
  }

  fn timeline(&self, arguments: Value) -> Result<ToolOutput, String> {
    let args: TimelineArgs = parse(arguments)?;
    let budget = Budget::from_args(
      args.exact.common.budget.as_deref(),
      args.exact.common.max_chars,
    );
    let (run, test) = self.resolve_test(&args.exact.run_id, &args.exact.test_id)?;
    let entries = self
      .repository
      .get_entries(&args.exact.run_id, &args.exact.test_id)
      .map_err(display_error)?;
    let focus = args
      .focus
      .unwrap_or_else(|| TimelineFocus::Failure.as_str().to_string());
    let selected = if focus == TimelineFocus::All.as_str() {
      entries.iter().take(budget.timeline_events).collect()
    } else {
      failure_window(&entries, budget.timeline_events)
    };

    let structured = json!({
      "app_name": run.app_name,
      "run_id": run.id,
      "test": test_json(&test),
      "focus": focus,
      "events": selected
        .iter()
        .map(|entry| entry_preview(entry, budget.string_chars))
        .collect::<Vec<_>>(),
      "total_events": entries.len(),
      "omitted_events": entries.len().saturating_sub(selected.len()),
      "fallback": fallback_message(),
    });
    Ok(output(structured, "Stove test timeline"))
  }

  fn trace(&self, arguments: Value) -> Result<ToolOutput, String> {
    let args: TraceArgs = parse(arguments)?;
    let budget = Budget::from_args(args.common.budget.as_deref(), args.common.max_chars);
    let (run, test, entries, spans) = if let Some(trace_id) = args.trace_id.as_deref() {
      let spans = self.repository.get_trace(trace_id).map_err(display_error)?;
      let run = spans
        .first()
        .and_then(|span| self.repository.get_run(&span.run_id).ok().flatten());
      let test = run
        .as_ref()
        .and_then(|run| correlated_test_for_trace(&self.repository, run, trace_id));
      let entries = test.as_ref().map_or_else(Vec::new, |test| {
        self
          .repository
          .get_entries(&test.run_id, &test.id)
          .unwrap_or_default()
      });
      (run, test, entries, spans)
    } else {
      let run_id = args
        .run_id
        .as_deref()
        .ok_or_else(|| "stove_trace requires run_id + test_id or trace_id".to_string())?;
      let test_id = args
        .test_id
        .as_deref()
        .ok_or_else(|| "stove_trace requires run_id + test_id or trace_id".to_string())?;
      let (run, test) = self.resolve_test(run_id, test_id)?;
      let entries = self
        .repository
        .get_entries(run_id, test_id)
        .map_err(display_error)?;
      let spans = self
        .repository
        .get_spans_for_test(run_id, test_id)
        .map_err(display_error)?;
      (Some(run), Some(test), entries, spans)
    };

    let view = args.view.unwrap_or_else(|| "critical_path".to_string());
    let structured = json!({
      "app_name": run.as_ref().map(|run| run.app_name.as_str()),
      "run_id": run.as_ref().map(|run| run.id.as_str()),
      "test": test.as_ref().map(test_json),
      "view": view,
      "trace": trace_summary(&spans, &entries, run.as_ref().map_or("", |run| &run.id), test.as_ref().map_or("", |test| &test.id), budget.trace_spans),
      "fallback": fallback_message(),
    });
    Ok(output(structured, "Stove trace evidence"))
  }

  fn snapshot(&self, arguments: Value) -> Result<ToolOutput, String> {
    let args: SnapshotArgs = parse(arguments)?;
    let budget = Budget::from_args(
      args.exact.common.budget.as_deref(),
      args.exact.common.max_chars,
    );
    let (run, test) = self.resolve_test(&args.exact.run_id, &args.exact.test_id)?;
    let mut snapshots = self
      .repository
      .get_snapshots(&args.exact.run_id, &args.exact.test_id)
      .map_err(display_error)?;
    if let Some(system) = &args.system {
      snapshots.retain(|snapshot| snapshot.system == *system);
    }

    let items = snapshots
      .iter()
      .take(budget.snapshots)
      .map(|snapshot| snapshot_detail(snapshot, args.json_pointer.as_deref(), budget.string_chars))
      .collect::<Vec<_>>();
    let structured = json!({
      "app_name": run.app_name,
      "run_id": run.id,
      "test": test_json(&test),
      "snapshots": items,
      "total_snapshots": snapshots.len(),
      "omitted_snapshots": snapshots.len().saturating_sub(items.len()),
      "fallback": fallback_message(),
    });
    Ok(output(structured, "Stove snapshots"))
  }

  fn raw_evidence(&self, arguments: Value) -> Result<ToolOutput, String> {
    let args: RawEvidenceArgs = parse(arguments)?;
    let budget = Budget::from_args(args.common.budget.as_deref(), args.common.max_chars);
    let kind = args.kind.to_ascii_lowercase();
    let evidence = match RawEvidenceKind::from_str(&kind) {
      Some(RawEvidenceKind::Entry) => {
        let run_id = args
          .run_id
          .as_deref()
          .ok_or_else(|| "raw entry lookup requires run_id and test_id".to_string())?;
        let test_id = args
          .test_id
          .as_deref()
          .ok_or_else(|| "raw entry lookup requires run_id and test_id".to_string())?;
        let entry = self
          .repository
          .get_entries(run_id, test_id)
          .map_err(display_error)?
          .into_iter()
          .find(|entry| entry.id == args.id)
          .ok_or_else(|| format!("entry {} was not found in {run_id}/{test_id}", args.id))?;
        json!({ "kind": RawEvidenceKind::Entry.as_str(), "evidence": entry_preview(&entry, budget.raw_string_chars) })
      }
      Some(RawEvidenceKind::Span) => {
        let spans = if let Some(trace_id) = args.trace_id.as_deref() {
          self.repository.get_trace(trace_id).map_err(display_error)?
        } else {
          let run_id = args
            .run_id
            .as_deref()
            .ok_or_else(|| "raw span lookup requires trace_id or run_id + test_id".to_string())?;
          let test_id = args
            .test_id
            .as_deref()
            .ok_or_else(|| "raw span lookup requires trace_id or run_id + test_id".to_string())?;
          self
            .repository
            .get_spans_for_test(run_id, test_id)
            .map_err(display_error)?
        };
        let span = spans
          .into_iter()
          .find(|span| span.id == args.id)
          .ok_or_else(|| format!("span {} was not found", args.id))?;
        json!({ "kind": RawEvidenceKind::Span.as_str(), "evidence": span_preview(&span, budget.raw_string_chars) })
      }
      Some(RawEvidenceKind::Snapshot) => {
        let run_id = args
          .run_id
          .as_deref()
          .ok_or_else(|| "raw snapshot lookup requires run_id and test_id".to_string())?;
        let test_id = args
          .test_id
          .as_deref()
          .ok_or_else(|| "raw snapshot lookup requires run_id and test_id".to_string())?;
        let snapshot = self
          .repository
          .get_snapshots(run_id, test_id)
          .map_err(display_error)?
          .into_iter()
          .find(|snapshot| snapshot.id == args.id)
          .ok_or_else(|| format!("snapshot {} was not found in {run_id}/{test_id}", args.id))?;
        json!({ "kind": RawEvidenceKind::Snapshot.as_str(), "evidence": snapshot_detail(&snapshot, None, budget.raw_string_chars) })
      }
      None => {
        return Err(format!(
          "kind must be one of: {}, {}, {}",
          RawEvidenceKind::Entry.as_str(),
          RawEvidenceKind::Span.as_str(),
          RawEvidenceKind::Snapshot.as_str()
        ));
      }
    };

    Ok(output(
      json!({ "raw_evidence": evidence, "fallback": fallback_message() }),
      "Raw Stove evidence",
    ))
  }

  fn resolve_test(&self, run_id: &str, test_id: &str) -> Result<(Run, Test), String> {
    let run = self
      .repository
      .get_run(run_id)
      .map_err(display_error)?
      .ok_or_else(|| format!("run `{run_id}` was not found"))?;
    let test = self
      .repository
      .get_tests_for_run(run_id)
      .map_err(display_error)?
      .into_iter()
      .find(|test| test.id == test_id)
      .ok_or_else(|| format!("test `{test_id}` was not found in run `{run_id}`"))?;
    Ok((run, test))
  }
}

fn selected_runs(
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

fn failure_item(run: &Run, test: &Test) -> Value {
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

fn test_json(test: &Test) -> Value {
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

fn timeline_summary(entries: &[Entry], run_id: &str, test_id: &str, max_events: usize) -> Value {
  let selected = failure_window(entries, max_events);
  json!({
    "total_events": entries.len(),
    "failed_entries": entries.iter().filter(|entry| is_failed_status(&entry.result)).count(),
    "events": selected.iter().map(|entry| compact_event(entry)).collect::<Vec<_>>(),
    "timeline_tool_call": exact_test_tool_call(ToolName::Timeline, run_id, test_id),
  })
}

fn failure_window(entries: &[Entry], max_events: usize) -> Vec<&Entry> {
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

fn trace_summary(
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

fn groups_have_running_runs(groups: &[Value]) -> bool {
  groups.iter().any(|group| {
    group.get("run_status").and_then(Value::as_str) == Some(RunStatusValue::Running.as_str())
  })
}

fn correlated_test_for_trace(repository: &Repository, run: &Run, trace_id: &str) -> Option<Test> {
  repository
    .get_tests_for_run(&run.id)
    .ok()?
    .into_iter()
    .find(|test| {
      repository
        .get_entries(&run.id, &test.id)
        .map(|entries| {
          entries
            .iter()
            .any(|entry| entry.trace_id.as_deref() == Some(trace_id))
        })
        .unwrap_or(false)
    })
}

fn is_failed_test(test: &Test) -> bool {
  matches!(test.status, TestStatus::Failed | TestStatus::Error)
}

fn is_failed_status(status: &TestStatus) -> bool {
  matches!(status, TestStatus::Failed | TestStatus::Error)
}

fn is_failed_span(span: &Span) -> bool {
  span.status.eq_ignore_ascii_case(STATUS_ERROR)
    || span
      .status
      .eq_ignore_ascii_case(RunStatusValue::Failed.as_str())
    || span.exception_type.is_some()
}

pub(super) fn tool_call(tool: ToolName, arguments: Value) -> Value {
  let mut call = serde_json::Map::new();
  call.insert("tool".to_string(), Value::String(tool.as_str().to_string()));
  call.insert("arguments".to_string(), arguments);
  Value::Object(call)
}

fn exact_test_tool_call(tool: ToolName, run_id: &str, test_id: &str) -> Value {
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

fn selector_rules() -> Value {
  json!({
    "app_name": "grouping/filter only; multiple runs may exist per app",
    "run_id": "canonical execution boundary",
    "test_id": "unique only within run_id",
    "exact_test_selector": [ArgName::RunId.as_str(), ArgName::TestId.as_str()],
  })
}

fn fallback_message() -> &'static str {
  "If Stove MCP is unavailable, incomplete, or ambiguous, fall back to normal test output, Stove failure reports, and logs."
}

fn output(structured: Value, heading: &str) -> ToolOutput {
  let text = format!("{heading}\n{}", compact_text(&structured));
  ToolOutput { structured, text }
}

fn compact_text(value: &Value) -> String {
  serde_json::to_string_pretty(value)
    .unwrap_or_else(|_| "Stove MCP result could not be rendered as JSON".to_string())
}

fn display_error(error: impl std::fmt::Display) -> String {
  error.to_string()
}
