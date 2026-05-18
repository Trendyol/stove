//! `stove_timeline` tool — windowed event timeline for a single test.

use serde_json::Value;
use serde_json::json;

use super::Analyzer;
use super::ToolOutput;
use super::common::display_error;
use super::common::failure_window;
use super::common::fallback_message;
use super::common::output;
use super::common::test_json;
use super::evidence::entry_preview;
use crate::mcp::args::Budget;
use crate::mcp::args::TimelineArgs;
use crate::mcp::args::parse;
use crate::mcp::contract::TimelineFocus;

impl Analyzer {
  pub(super) fn timeline(&self, arguments: Value) -> Result<ToolOutput, String> {
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
}
