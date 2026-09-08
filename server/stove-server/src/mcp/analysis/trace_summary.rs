//! Shared trace selection and rendering for trace tools and failure summaries.

use serde_json::{Value, json};
use std::collections::{BTreeMap, BTreeSet, HashMap, HashSet};

use super::common::{exact_test_tool_call, is_failed_status};
use super::evidence::{clip_string, span_preview};
use crate::mcp::args::Budget;
use crate::mcp::contract::{ToolName, TraceView};
use crate::navigation::reference;
use crate::storage::models::{Entry, Span};

#[derive(Clone, Copy)]
pub(super) enum TraceDetail {
  Summary,
  Diagnosis,
  View(TraceView),
}

pub(super) fn summarize(
  spans: &[Span],
  entries: &[Entry],
  run_id: &str,
  test_id: &str,
  budget: Budget,
  detail: TraceDetail,
) -> Value {
  if spans.is_empty() {
    let trace_ids = trace_ids_from_entries(entries);
    let mut result = json!({
      "trace_status": "uncorrelated",
      "trace_ids": trace_ids,
      "total_spans": 0,
      "omitted_spans": 0,
      "failed_spans": 0,
      "exception_spans": 0,
      "message": "No spans were correlated to this test. Fall back to timeline entries and logs if trace evidence is needed.",
    });
    if matches!(detail, TraceDetail::Diagnosis) {
      result["trace_ids"] = json!(trace_ids.iter().take(1).collect::<Vec<_>>());
      result["omitted_traces"] = json!(trace_ids.len().saturating_sub(1));
    }
    return result;
  }

  let ranked_trace_ids = ranked_trace_ids(spans, entries);
  let mut result = json!({
    "trace_status": "correlated",
    "trace_ids": ranked_trace_ids,
    "total_spans": spans.len(),
    "failed_spans": spans.iter().filter(|span| is_failed_span(span)).count(),
    "exception_spans": spans.iter().filter(|span| span.exception_type.is_some()).count(),
  });
  if matches!(detail, TraceDetail::Diagnosis) {
    result["trace_ids"] = json!(ranked_trace_ids.iter().take(1).collect::<Vec<_>>());
    result["omitted_traces"] = json!(ranked_trace_ids.len().saturating_sub(1));
    result["selection"] = json!(
      "One trace, preferring errors, then failed-entry correlation, then span count. Path retains the target and nearest ancestors; missing parents or omitted spans can leave gaps. Correlation is not proof of causation."
    );
  }
  if !run_id.is_empty() && !test_id.is_empty() {
    result["trace_tool_call"] = exact_test_tool_call(ToolName::Trace, run_id, test_id);
  }
  let selected = select_spans(spans, &ranked_trace_ids, budget.trace_spans, detail);
  let returned: HashSet<_> = selected
    .iter()
    .flat_map(|(_, spans)| spans.iter().map(|span| span.id))
    .collect();
  for (field, spans) in selected {
    result[field] = Value::Array(
      spans
        .into_iter()
        .map(|span| match detail {
          TraceDetail::Diagnosis => json!({
            "navigation": reference(run_id, Some(test_id), Some(("span", span.id))),
            "id": span.id, "trace_id": span.trace_id, "span_id": span.span_id,
            "parent_span_id": span.parent_span_id,
            "operation_name": clip_string(&span.operation_name, budget.string_chars),
            "service_name": clip_string(&span.service_name, budget.string_chars),
            "start_time_nanos": span.start_time_nanos, "end_time_nanos": span.end_time_nanos,
            "status": span.status,
          }),
          _ => span_preview(span, budget.string_chars),
        })
        .collect(),
    );
  }
  result["omitted_spans"] = json!(spans.len().saturating_sub(returned.len()));
  result
}

fn select_spans<'a>(
  spans: &'a [Span],
  ranked_trace_ids: &[String],
  max_spans: usize,
  detail: TraceDetail,
) -> Vec<(&'static str, Vec<&'a Span>)> {
  let path = || {
    ranked_trace_ids.first().map_or_else(Vec::new, |trace_id| {
      critical_path_for_trace(spans, trace_id, max_spans)
    })
  };
  let exceptions = || {
    spans
      .iter()
      .filter(|span| span.exception_type.is_some())
      .take(max_spans)
      .collect()
  };
  match detail {
    TraceDetail::Summary => vec![("critical_path", path()), ("exceptions", exceptions())],
    TraceDetail::Diagnosis | TraceDetail::View(TraceView::CriticalPath) => {
      vec![("critical_path", path())]
    }
    TraceDetail::View(TraceView::Exceptions) => vec![("exceptions", exceptions())],
    TraceDetail::View(TraceView::Tree) => {
      vec![("spans", tree_spans(spans, ranked_trace_ids, max_spans))]
    }
  }
}

fn tree_spans<'a>(
  spans: &'a [Span],
  ranked_trace_ids: &[String],
  max_spans: usize,
) -> Vec<&'a Span> {
  // Parent IDs preserve the tree without recursive output or duplicated subtrees.
  let mut by_trace: HashMap<&str, Vec<&Span>> = HashMap::new();
  for span in spans {
    by_trace.entry(&span.trace_id).or_default().push(span);
  }
  ranked_trace_ids
    .iter()
    .flat_map(|trace_id| by_trace.remove(trace_id.as_str()).unwrap_or_default())
    .take(max_spans)
    .collect()
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

  // Failure evidence outranks volume: a large healthy trace must not hide an error.
  let mut scores: BTreeMap<String, (bool, bool, usize)> = BTreeMap::new();
  for span in spans {
    let score = scores.entry(span.trace_id.clone()).or_default();
    score.0 |= is_failed_span(span);
    score.1 |= failed_entry_traces.contains(&span.trace_id);
    score.2 += 1;
  }

  let mut ranked: Vec<_> = scores.into_iter().collect();
  ranked.sort_by(|left, right| right.1.cmp(&left.1).then_with(|| left.0.cmp(&right.0)));
  ranked.into_iter().map(|(trace_id, _)| trace_id).collect()
}

fn critical_path_for_trace<'a>(
  spans: &'a [Span],
  trace_id: &str,
  max_spans: usize,
) -> Vec<&'a Span> {
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
  while path.len() < max_spans {
    let Some(span) = current else { break };
    if !seen.insert(span.span_id.clone()) {
      break;
    }
    path.push(span);
    current = span
      .parent_span_id
      .as_deref()
      .and_then(|parent_id| by_span_id.get(parent_id).copied());
  }
  // Preserve the failed target when its ancestor chain exceeds the budget.
  path.reverse();
  path
}

fn is_failed_span(span: &Span) -> bool {
  span.status == crate::storage::models::SpanStatus::Error || span.exception_type.is_some()
}
