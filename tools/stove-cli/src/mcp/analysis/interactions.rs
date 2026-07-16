//! `stove_interactions` tool — mock exchanges and warnings for a test or a whole run.
//!
//! Every request that reaches a `WireMock` or gRPC Mock is recorded as an interaction,
//! matched or not, with proven-only attribution. Run scope includes the unattributed
//! lane (`test_id == null`): evidence with no provable link to any test, never guessed
//! into one.

use serde_json::Value;
use serde_json::json;

use super::Analyzer;
use super::ToolOutput;
use super::common::display_error;
use super::common::fallback_message;
use super::common::output;
use super::evidence::interaction_preview;
use super::evidence::warning_preview;
use crate::mcp::args::Budget;
use crate::mcp::args::InteractionsArgs;
use crate::mcp::args::parse;

impl Analyzer {
  pub(super) fn interactions(&self, arguments: Value) -> Result<ToolOutput, String> {
    let args: InteractionsArgs = parse(arguments)?;
    let budget = Budget::from_args(args.common.budget.as_deref(), args.common.max_chars);
    let limit = args.common.limit().min(budget.interactions.max(1));

    let (interactions, warnings, scope) = if let Some(test_id) = args.test_id.as_deref() {
      self.resolve_test(&args.run_id, test_id)?;
      (
        self
          .repository
          .get_mock_interactions_for_test(&args.run_id, test_id)
          .map_err(display_error)?,
        self
          .repository
          .get_mock_warnings_for_test(&args.run_id, test_id)
          .map_err(display_error)?,
        "test",
      )
    } else {
      self
        .repository
        .get_run(&args.run_id)
        .map_err(display_error)?
        .ok_or_else(|| format!("run `{}` was not found", args.run_id))?;
      (
        self
          .repository
          .get_mock_interactions_for_run(&args.run_id)
          .map_err(display_error)?,
        self
          .repository
          .get_mock_warnings_for_run(&args.run_id)
          .map_err(display_error)?,
        "run",
      )
    };

    let unmatched_count = interactions
      .iter()
      .filter(|interaction| !interaction.matched)
      .count();
    let unattributed_count = interactions
      .iter()
      .filter(|interaction| interaction.test_id.is_none())
      .count();

    let structured = json!({
      "run_id": args.run_id,
      "test_id": args.test_id,
      "scope": scope,
      "interactions": interactions
        .iter()
        .take(limit)
        .map(|interaction| interaction_preview(interaction, budget.string_chars))
        .collect::<Vec<_>>(),
      "warnings": warnings
        .iter()
        .take(limit)
        .map(|warning| warning_preview(warning, budget.string_chars))
        .collect::<Vec<_>>(),
      "total_interactions": interactions.len(),
      "unmatched_interactions": unmatched_count,
      "unattributed_interactions": unattributed_count,
      "total_warnings": warnings.len(),
      "omitted": {
        "interactions": interactions.len().saturating_sub(limit.min(interactions.len())),
        "warnings": warnings.len().saturating_sub(limit.min(warnings.len())),
      },
      "attribution_rules": "Attribution is proven-only (X-Stove-Test-Id header, W3C baggage, or the matched stub's registration tag). UNATTRIBUTED entries have no provable owner; do not guess one from timing or names.",
      "fallback": fallback_message(),
    });
    Ok(output(structured, "Stove mock interactions and warnings"))
  }
}
