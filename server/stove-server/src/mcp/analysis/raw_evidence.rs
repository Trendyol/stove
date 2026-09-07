//! Exact raw evidence uses the same ownership checks as dashboard citations.
use super::{
  AnalysisOutput, Analyzer,
  common::{display_error, fallback_message, output},
  evidence::{entry_preview, interaction_preview, snapshot_detail, span_preview, warning_preview},
};
use crate::{
  focus::{self, EvidenceKind, EvidenceTarget},
  mcp::args::{Budget, RawEvidenceArgs, parse},
};
use serde_json::{Value, json};

impl Analyzer {
  pub(super) fn raw_evidence(&self, arguments: Value) -> Result<AnalysisOutput, String> {
    let args: RawEvidenceArgs = parse(arguments)?;
    let budget = Budget::from_args(args.common.budget.as_deref(), args.common.max_chars);
    let kind: EvidenceKind = serde_json::from_value(json!(args.kind.to_ascii_lowercase()))
      .map_err(|_| {
        "kind must be one of: entry, span, snapshot, interaction, warning".to_string()
      })?;
    let mut run_id = args.run_id.clone();
    let mut test_id = args.test_id.clone();
    if matches!(kind, EvidenceKind::Span) && args.trace_id.is_some() {
      let span = self
        .repository
        .get_trace_span(args.trace_id.as_deref().unwrap(), args.id)
        .map_err(display_error)?
        .ok_or_else(|| format!("span {} was not found", args.id))?;
      if run_id.as_deref().is_some_and(|run| run != span.run_id) {
        return Err("span is not in the requested run".into());
      }
      run_id = Some(span.run_id);
    }
    let run_id =
      run_id.ok_or_else(|| "raw evidence requires run_id (or trace_id for spans)".to_string())?;
    // Run-level raw mock lookups may refer to either proven or ambient evidence.
    // The returned citation carries the actual owner, never an inferred test.
    if test_id.is_none() {
      match kind {
        EvidenceKind::Interaction => {
          test_id = self
            .repository
            .get_mock_interaction(&run_id, args.id)
            .map_err(display_error)?
            .and_then(|item| item.test_id);
        }
        EvidenceKind::Warning => {
          test_id = self
            .repository
            .get_mock_warning(&run_id, args.id)
            .map_err(display_error)?
            .and_then(|item| item.test_id);
        }
        _ => {}
      }
    }
    let target =
      focus::resolve_target(&self.repository, &run_id, test_id.as_deref(), kind, args.id)
        .map_err(display_error)?;
    let (kind, evidence) = match target {
      EvidenceTarget::Entry(item) => ("entry", entry_preview(&item, budget.raw_string_chars)),
      EvidenceTarget::Span(item) => ("span", span_preview(&item, budget.raw_string_chars)),
      EvidenceTarget::Snapshot(item) => (
        "snapshot",
        snapshot_detail(&item, None, budget.raw_string_chars),
      ),
      EvidenceTarget::Interaction(item) => (
        "interaction",
        interaction_preview(&item, budget.raw_string_chars),
      ),
      EvidenceTarget::Warning(item) => ("warning", warning_preview(&item, budget.raw_string_chars)),
    };
    Ok(output(
      json!({ "run_id": run_id, "test_id": test_id, "raw_evidence": { "kind": kind, "evidence": evidence }, "fallback": fallback_message() }),
      "Raw Stove evidence",
    ))
  }
}
