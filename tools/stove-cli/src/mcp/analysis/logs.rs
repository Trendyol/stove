//! `stove_logs` tool — query captured application log records for a test or
//! trace with severity/logger/text filters.

use serde_json::Value;
use serde_json::json;

use super::Analyzer;
use super::ToolOutput;
use super::common::display_error;
use super::common::fallback_message;
use super::common::output;
use super::evidence::log_preview;
use crate::mcp::args::Budget;
use crate::mcp::args::LogsArgs;
use crate::mcp::args::parse;
use crate::mcp::contract::LogsFocus;
use crate::storage::models::LogQuery;

impl Analyzer {
  pub(super) fn logs(&self, arguments: Value) -> Result<ToolOutput, String> {
    let args: LogsArgs = parse(arguments)?;
    let budget = Budget::from_args(args.common.budget.as_deref(), args.common.max_chars);
    let focus = args
      .focus
      .clone()
      .unwrap_or_else(|| LogsFocus::Failure.as_str().to_string());
    let query = LogQuery {
      level: args.level.clone(),
      min_severity: if args.level.is_none() && focus == LogsFocus::Failure.as_str() {
        Some(13)
      } else {
        None
      },
      logger: args.logger.clone(),
      q: args.q.clone(),
      limit: args.common.limit().min(budget.logs),
      ..LogQuery::default()
    };

    let logs = if let Some(trace_id) = args.trace_id.as_deref() {
      self
        .repository
        .get_logs_for_trace(trace_id, &query)
        .map_err(display_error)?
    } else {
      let run_id = args
        .run_id
        .as_deref()
        .ok_or_else(|| "stove_logs requires run_id + test_id or trace_id".to_string())?;
      let test_id = args
        .test_id
        .as_deref()
        .ok_or_else(|| "stove_logs requires run_id + test_id or trace_id".to_string())?;
      self
        .repository
        .get_logs_for_test(run_id, test_id, &query)
        .map_err(display_error)?
    };

    let structured = json!({
      "focus": focus,
      "filters": {
        "level": args.level,
        "logger": args.logger,
        "q": args.q,
      },
      "logs": logs.iter().map(|log| log_preview(log, budget.string_chars)).collect::<Vec<_>>(),
      "total_returned": logs.len(),
      "omitted_logs": 0,
      "dropped_logs": logs.iter().filter(|log| log.correlation_source == "DROPPED_MARKER").count(),
      "fallback": fallback_message(),
    });
    Ok(output(structured, "Stove logs"))
  }
}
