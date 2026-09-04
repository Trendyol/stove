//! `stove_trace` tool — span-centric view, by trace id or test selector.

use serde_json::Value;
use serde_json::json;

use super::Analyzer;
use super::ToolOutput;
use super::common::correlated_test_for_trace;
use super::common::display_error;
use super::common::fallback_message;
use super::common::output;
use super::common::test_json;
use super::common::trace_summary;
use crate::mcp::args::Budget;
use crate::mcp::args::TraceArgs;
use crate::mcp::args::parse;

impl Analyzer {
  pub(super) fn trace(&self, arguments: Value) -> Result<ToolOutput, String> {
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
}
