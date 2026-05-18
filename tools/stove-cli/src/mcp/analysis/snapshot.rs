//! `stove_snapshot` tool — system-state snapshots captured during a test.

use serde_json::Value;
use serde_json::json;

use super::Analyzer;
use super::ToolOutput;
use super::common::display_error;
use super::common::fallback_message;
use super::common::output;
use super::common::test_json;
use super::evidence::snapshot_detail;
use crate::mcp::args::Budget;
use crate::mcp::args::SnapshotArgs;
use crate::mcp::args::parse;

impl Analyzer {
  pub(super) fn snapshot(&self, arguments: Value) -> Result<ToolOutput, String> {
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
}
