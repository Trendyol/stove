//! Deterministic evidence ranking. No model calls or invented causal conclusions.
use serde_json::{Value, json};

use super::common::is_failed_status;
use super::evidence::{clip_opt, is_sensitive_key, preview_field};
use super::test_evidence::TestEvidence;
use crate::mcp::args::Budget;
use crate::navigation::{Navigation, component, reference};
use crate::storage::models::{
  Entry, MockInteraction, MockWarning, Snapshot, Span, SpanStatus, Test,
};

const MAX_FINDINGS: usize = 8;
const MAX_SCAN_NODES: usize = 50_000;
const MAX_PAYLOAD_BYTES: usize = 2_000_000;

// Declaration order is the evidence ranking contract.
#[derive(Clone, Copy, PartialEq, Eq, PartialOrd, Ord)]
enum Priority {
  ErrorSpan,
  MockMismatch,
  AssertionMismatch,
  DiagnosticField,
  Exception,
  FailedAction,
  MockWarning,
  TestError,
}

pub(super) struct DiagnosticFindings {
  pub actionable_findings: usize,
  pub findings: Vec<Value>,
  pub coverage: Value,
}

struct RankedFinding {
  priority: Priority,
  evidence: Value,
  occurrences: u64,
}

pub(super) fn collect(test: &Test, evidence: &TestEvidence, budget: Budget) -> DiagnosticFindings {
  let mut findings = Findings::new(budget);
  findings.exceptions(&evidence.spans);
  findings.interactions(&evidence.interactions);
  findings.assertions(&evidence.entries);
  findings.payloads(&evidence.entries, &evidence.snapshots);
  findings.warnings(&evidence.warnings);
  findings.test_error(test);
  findings.finish()
}

struct PayloadSource<'a> {
  field: &'a str,
  kind: &'static str,
  id: i64,
  navigation: Navigation,
}

fn raw_call(kind: &str, id: i64, run: &str, test: Option<&str>) -> Value {
  json!({"tool": "stove_raw_evidence", "arguments": {"kind": kind, "id": id, "run_id": run, "test_id": test}})
}

struct Findings {
  budget: Budget,
  ranked: Vec<RankedFinding>,
  candidates: usize,
  scanned: usize,
  skipped_payloads: usize,
  malformed_payloads: usize,
  scan_limited: bool,
}

impl Findings {
  fn new(budget: Budget) -> Self {
    Self {
      budget,
      ranked: Vec::new(),
      candidates: 0,
      scanned: 0,
      skipped_payloads: 0,
      malformed_payloads: 0,
      scan_limited: false,
    }
  }

  fn text(&self, value: &str) -> Value {
    clip_opt(Some(value), self.budget.string_chars)
  }

  fn push(&mut self, priority: Priority, finding: Value) {
    self.candidates += 1;
    // Repeated mock/exception text need not consume the whole evidence packet.
    if let Some(existing) = self.ranked.iter_mut().find(|existing| {
      let existing = &existing.evidence;
      existing["kind"] == finding["kind"]
        && existing["message"] == finding["message"]
        && existing["location"] == finding["location"]
        && existing["expected"] == finding["expected"]
        && existing["actual"] == finding["actual"]
    }) {
      existing.occurrences += 1;
      return;
    }
    self.ranked.push(RankedFinding {
      priority,
      evidence: finding,
      occurrences: 1,
    });
    self.ranked.sort_by_key(|finding| finding.priority);
    self.ranked.truncate(MAX_FINDINGS);
  }

  fn exceptions(&mut self, spans: &[Span]) {
    for span in spans
      .iter()
      .filter(|span| span.status == SpanStatus::Error || span.exception_type.is_some())
    {
      let stack: Vec<String> = span
        .exception_stack_trace
        .as_deref()
        .map(|raw| {
          serde_json::from_str(raw).unwrap_or_else(|_| raw.lines().map(str::to_owned).collect())
        })
        .unwrap_or_default();
      self.push(if span.status == SpanStatus::Error {Priority::ErrorSpan} else {Priority::Exception}, json!({
        "kind": "exception", "message": self.text(span.exception_message.as_deref().unwrap_or("Span recorded an error without an exception message")),
        "exception_type": clip_opt(span.exception_type.as_deref(), self.budget.string_chars),
        "location": {"service": self.text(&span.service_name), "operation": self.text(&span.operation_name)},
        "trace_id": span.trace_id, "span_id": span.span_id,
        "stack_excerpt": stack.iter().take(6).map(|line| self.text(line)).collect::<Vec<_>>(),
        "omitted_stack_lines": stack.len().saturating_sub(6),
        "navigation": reference(&span.run_id, None, Some(("span", span.id))),
        "raw_tool_call": raw_call("span", span.id, &span.run_id, None),
        "next_action": "Inspect this operation and exception at the recorded stack locations. Verify causality against the failing assertion."
      }));
    }
  }

  fn interactions(&mut self, interactions: &[MockInteraction]) {
    for interaction in interactions.iter().filter(|item| !item.matched) {
      for message in &interaction.near_misses {
        self.push(Priority::MockMismatch, json!({"kind": "mock_mismatch", "message": self.text(message),
          "location": {"system": self.text(&interaction.system), "method": self.text(&interaction.method), "target": self.text(&interaction.target)},
          "attribution": interaction.attribution, "status": self.text(&interaction.status),
          "request": preview_field(interaction.request_body.as_deref(), self.budget.string_chars),
          "navigation": reference(&interaction.run_id, interaction.test_id.as_deref(), Some(("interaction", interaction.id))),
          "raw_tool_call": raw_call("interaction", interaction.id, &interaction.run_id, interaction.test_id.as_deref()),
          "next_action": "Compare the recorded request with the expected mock matching rule."
        }));
      }
    }
  }

  fn assertions(&mut self, entries: &[Entry]) {
    for entry in entries
      .iter()
      .filter(|entry| is_failed_status(&entry.result))
    {
      let mismatch = entry.expected.as_deref().is_some_and(|v| !v.is_empty())
        && entry.actual.as_deref().is_some_and(|v| !v.is_empty());
      self.push(if mismatch {Priority::AssertionMismatch} else {Priority::FailedAction}, json!({
        "kind": if mismatch {"assertion_mismatch"} else {"failed_action"},
        "message": clip_opt(entry.error.as_deref(), self.budget.string_chars),
        "location": {"system": self.text(&entry.system), "action": self.text(&entry.action)},
        "expected": preview_field(entry.expected.as_deref(), self.budget.string_chars),
        "actual": preview_field(entry.actual.as_deref(), self.budget.string_chars),
        "attempt_count": entry.attempt_count, "failure_count": entry.failure_count,
        "navigation": reference(&entry.run_id, Some(&entry.test_id), Some(("entry", entry.id))),
        "raw_tool_call": raw_call("entry", entry.id, &entry.run_id, Some(&entry.test_id)),
        "next_action": "Inspect the cited assertion and compare expected with actual; an earlier exception or mock mismatch may explain the symptom."
      }));
    }
  }

  fn payloads(&mut self, entries: &[Entry], snapshots: &[Snapshot]) {
    // Only failing attempts contribute entry payload signals; passing retries
    // and unrelated tests must not create a diagnosis.
    for entry in entries
      .iter()
      .filter(|entry| is_failed_status(&entry.result))
    {
      for (field, raw) in [
        ("output", &entry.output),
        ("actual", &entry.actual),
        ("input", &entry.input),
      ] {
        if let Some(raw) = raw {
          self.scan_payload(
            raw,
            &PayloadSource {
              field,
              kind: "entry",
              id: entry.id,
              navigation: reference(
                &entry.run_id,
                Some(&entry.test_id),
                Some(("entry", entry.id)),
              ),
            },
          );
        }
      }
    }
    let mut ordered: Vec<_> = snapshots.iter().collect();
    ordered.sort_by_key(|snapshot| {
      (
        snapshot.trigger != "FAILURE",
        &snapshot.captured_at,
        snapshot.id,
      )
    });
    for snapshot in ordered {
      self.scan_payload(
        &snapshot.state_json,
        &PayloadSource {
          field: "state",
          kind: "snapshot",
          id: snapshot.id,
          navigation: reference(
            &snapshot.run_id,
            Some(&snapshot.test_id),
            Some(("snapshot", snapshot.id)),
          ),
        },
      );
    }
  }

  fn scan_payload(&mut self, raw: &str, source: &PayloadSource<'_>) {
    if raw.len() > MAX_PAYLOAD_BYTES || self.scanned >= MAX_SCAN_NODES {
      self.skipped_payloads += 1;
      return;
    }
    // Plain text fields are represented by the assertion, not malformed JSON.
    if !raw.trim_start().starts_with(['{', '[']) {
      return;
    }
    match serde_json::from_str::<Value>(raw) {
      Ok(value) => self.visit(&value, "", source),
      Err(_) => self.malformed_payloads += 1,
    }
  }

  fn visit(&mut self, value: &Value, pointer: &str, source: &PayloadSource<'_>) {
    if self.scanned >= MAX_SCAN_NODES {
      self.scan_limited = true;
      return;
    }
    self.scanned += 1;
    match value {
      Value::Object(map) => {
        for (key, value) in map {
          if is_sensitive_key(key) {
            continue;
          }
          if self.scanned >= MAX_SCAN_NODES {
            self.scan_limited = true;
            break;
          }
          let diagnostic = matches!(
            key.to_ascii_lowercase().as_str(),
            "diagnosis" | "error" | "error_message" | "exception_message" | "failure_reason"
          ) || (key.eq_ignore_ascii_case("message")
            && matches!(pointer.rsplit('/').next(), Some("error" | "exception")));
          let pointer = format!("{pointer}/{}", key.replace('~', "~0").replace('/', "~1"));
          if diagnostic
            && let Some(message) = value.as_str().filter(|message| !message.trim().is_empty())
          {
            let mut nav = source.navigation.clone();
            if source.kind == "snapshot" {
              nav.path.push_str("&pointer=");
              nav.path.push_str(&component(&pointer));
            }
            self.push(Priority::DiagnosticField, json!({"kind": "diagnostic_field", "message": self.text(message),
              "location": {"field": source.field, "json_pointer": pointer}, "navigation": nav,
              "raw_tool_call": raw_call(source.kind, source.id, &source.navigation.run_id, source.navigation.test_id.as_deref()),
              "next_action": "Check this recorded diagnostic against the assertion; a payload diagnostic is a candidate explanation, not proof of causality."
            }));
          }
          self.visit(value, &pointer, source);
        }
      }
      Value::Array(items) => {
        for (index, item) in items.iter().enumerate() {
          if self.scanned >= MAX_SCAN_NODES {
            self.scan_limited = true;
            break;
          }
          self.visit(item, &format!("{pointer}/{index}"), source);
        }
      }
      _ => {}
    }
  }

  fn warnings(&mut self, warnings: &[MockWarning]) {
    for warning in warnings {
      self.push(Priority::MockWarning, json!({"kind": "mock_warning", "message": self.text(&warning.message),
        "location": {"system": self.text(&warning.system), "warning_kind": self.text(&warning.kind)},
      "navigation": reference(&warning.run_id, warning.test_id.as_deref(), Some(("warning", warning.id))),
      "raw_tool_call": raw_call("warning", warning.id, &warning.run_id, warning.test_id.as_deref()),
        "next_action": "Check mock configuration. A warning alone does not establish a test failure."
      }));
    }
  }

  fn test_error(&mut self, test: &Test) {
    if let Some(error) = &test.error {
      self.push(Priority::TestError, json!({"kind": "test_error", "message": self.text(error),
        "navigation": reference(&test.run_id, Some(&test.id), None),
        "next_action": "Use the recorded test error and source. More specific evidence was not necessarily captured."
      }));
    }
  }

  fn finish(self) -> DiagnosticFindings {
    let actionable_findings = self
      .ranked
      .iter()
      .filter(|finding| finding.priority <= Priority::DiagnosticField)
      .count();
    let represented: u64 = self.ranked.iter().map(|finding| finding.occurrences).sum();
    DiagnosticFindings {
      actionable_findings,
      findings: self
        .ranked
        .into_iter()
        .map(|mut finding| {
          finding.evidence["occurrences"] = json!(finding.occurrences);
          finding.evidence
        })
        .collect(),
      coverage: json!({"candidate_occurrences": self.candidates, "omitted_occurrences": (self.candidates as u64).saturating_sub(represented),
        "scanned_json_nodes": self.scanned, "scan_node_limit": MAX_SCAN_NODES,
        "scan_limited": self.scan_limited, "skipped_payloads": self.skipped_payloads,
        "malformed_payloads": self.malformed_payloads, "max_payload_bytes": MAX_PAYLOAD_BYTES,
        "limitations": "Only explicit diagnostic JSON keys are extracted. Absence is not evidence of correctness. Messages and stack excerpts may be clipped; recorded evidence cannot guarantee a root cause or code fix."
      }),
    }
  }
}

#[cfg(test)]
mod tests {
  use super::*;

  fn source() -> PayloadSource<'static> {
    PayloadSource {
      field: "state",
      kind: "snapshot",
      id: 1,
      navigation: reference("run", Some("test"), Some(("snapshot", 1))),
    }
  }

  #[test]
  fn scanner_reports_limits_and_malformed_data_instead_of_claiming_completeness() {
    let mut findings = Findings::new(Budget::from_args(Some("tiny"), None));
    findings.scan_payload("{broken", &source());
    findings.scan_payload(&" ".repeat(MAX_PAYLOAD_BYTES + 1), &source());
    let raw =
      json!({"many": vec![0; MAX_SCAN_NODES + 1], "z": {"diagnosis": "not inspected"}}).to_string();
    findings.scan_payload(&raw, &source());
    let result = findings.finish();
    assert_eq!(result.coverage["scanned_json_nodes"], MAX_SCAN_NODES);
    assert_eq!(result.coverage["scan_limited"], true);
    assert_eq!(result.coverage["malformed_payloads"], 1);
    assert_eq!(result.coverage["skipped_payloads"], 1);
    assert!(result.findings.is_empty());
  }

  #[test]
  fn nested_errors_are_evidence_and_sensitive_ancestors_are_skipped() {
    let mut findings = Findings::new(Budget::from_args(None, None));
    findings.scan_payload(
      &json!({"error":{"message":"duplicate key"},
      "credentials":{"error":{"message":"hidden"}}, "description":"not a diagnosis"})
      .to_string(),
      &source(),
    );
    let result = findings.finish();
    assert_eq!(result.findings[0]["message"], "duplicate key");
    assert_eq!(
      result.findings[0]["location"]["json_pointer"],
      "/error/message"
    );
    assert!(!json!(result.findings).to_string().contains("hidden"));
    assert_eq!(result.findings.len(), 1);
  }
}
