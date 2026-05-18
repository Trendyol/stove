//! `stove_raw_evidence` tool — fetch a single entry/span/snapshot by id with
//! larger string budgets than the summarizing tools allow.

use serde_json::Value;
use serde_json::json;

use super::Analyzer;
use super::ToolOutput;
use super::common::display_error;
use super::common::fallback_message;
use super::common::output;
use super::evidence::entry_preview;
use super::evidence::snapshot_detail;
use super::evidence::span_preview;
use crate::mcp::args::Budget;
use crate::mcp::args::RawEvidenceArgs;
use crate::mcp::args::parse;
use crate::mcp::contract::RawEvidenceKind;

impl Analyzer {
  pub(super) fn raw_evidence(&self, arguments: Value) -> Result<ToolOutput, String> {
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
          .ok_or_else(|| {
            let id = args.id;
            format!("entry {id} was not found in {run_id}/{test_id}")
          })?;
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
          .ok_or_else(|| {
            let id = args.id;
            format!("span {id} was not found")
          })?;
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
          .ok_or_else(|| {
            let id = args.id;
            format!("snapshot {id} was not found in {run_id}/{test_id}")
          })?;
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
}
