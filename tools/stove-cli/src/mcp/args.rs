use serde::Deserialize;
use serde_json::Value;

use super::contract::BudgetValue;

const DEFAULT_LIMIT: usize = 20;
const MAX_LIMIT: usize = 100;

#[derive(Debug, Deserialize, Default)]
pub(crate) struct CommonArgs {
  pub(crate) limit: Option<usize>,
  pub(crate) budget: Option<String>,
  pub(crate) max_chars: Option<usize>,
}

impl CommonArgs {
  pub(crate) fn limit(&self) -> usize {
    self.limit.unwrap_or(DEFAULT_LIMIT).clamp(1, MAX_LIMIT)
  }
}

#[derive(Debug, Deserialize, Default)]
pub(crate) struct ListArgs {
  #[serde(flatten)]
  common: CommonArgs,
}

impl ListArgs {
  pub(crate) fn limit(&self) -> usize {
    self.common.limit()
  }
}

#[derive(Debug, Deserialize, Default)]
pub(crate) struct RunsArgs {
  #[serde(flatten)]
  pub(crate) common: CommonArgs,
  pub(crate) app_name: Option<String>,
  pub(crate) status: Option<String>,
}

#[derive(Debug, Deserialize, Default)]
pub(crate) struct FailuresArgs {
  #[serde(flatten)]
  pub(crate) common: CommonArgs,
  pub(crate) app_name: Option<String>,
  pub(crate) run_id: Option<String>,
}

#[derive(Debug, Deserialize)]
pub(crate) struct ExactTestArgs {
  #[serde(flatten)]
  pub(crate) common: CommonArgs,
  pub(crate) run_id: String,
  pub(crate) test_id: String,
}

#[derive(Debug, Deserialize)]
pub(crate) struct TimelineArgs {
  #[serde(flatten)]
  pub(crate) exact: ExactTestArgs,
  pub(crate) focus: Option<String>,
}

#[derive(Debug, Deserialize, Default)]
pub(crate) struct TraceArgs {
  #[serde(flatten)]
  pub(crate) common: CommonArgs,
  pub(crate) run_id: Option<String>,
  pub(crate) test_id: Option<String>,
  pub(crate) trace_id: Option<String>,
  pub(crate) view: Option<String>,
}

#[derive(Debug, Deserialize, Default)]
pub(crate) struct LogsArgs {
  #[serde(flatten)]
  pub(crate) common: CommonArgs,
  pub(crate) run_id: Option<String>,
  pub(crate) test_id: Option<String>,
  pub(crate) trace_id: Option<String>,
  pub(crate) focus: Option<String>,
  pub(crate) level: Option<String>,
  pub(crate) logger: Option<String>,
  pub(crate) q: Option<String>,
}

#[derive(Debug, Deserialize)]
pub(crate) struct SnapshotArgs {
  #[serde(flatten)]
  pub(crate) exact: ExactTestArgs,
  pub(crate) system: Option<String>,
  pub(crate) json_pointer: Option<String>,
}

#[derive(Debug, Deserialize)]
pub(crate) struct RawEvidenceArgs {
  #[serde(flatten)]
  pub(crate) common: CommonArgs,
  pub(crate) kind: String,
  pub(crate) id: i64,
  pub(crate) run_id: Option<String>,
  pub(crate) test_id: Option<String>,
  pub(crate) trace_id: Option<String>,
}

#[derive(Debug, Clone, Copy)]
pub(crate) struct Budget {
  pub(crate) string_chars: usize,
  pub(crate) raw_string_chars: usize,
  pub(crate) timeline_events: usize,
  pub(crate) trace_spans: usize,
  pub(crate) logs: usize,
  pub(crate) failed_entries: usize,
  pub(crate) snapshots: usize,
}

impl Budget {
  pub(crate) fn from_args(name: Option<&str>, max_chars: Option<usize>) -> Self {
    let budget_name = name
      .and_then(BudgetValue::from_str)
      .unwrap_or(BudgetValue::Compact);
    let mut budget = match budget_name {
      BudgetValue::Tiny => Self {
        string_chars: 240,
        raw_string_chars: 800,
        timeline_events: 5,
        trace_spans: 8,
        logs: 10,
        failed_entries: 3,
        snapshots: 3,
      },
      BudgetValue::Full => Self {
        string_chars: 2_000,
        raw_string_chars: 12_000,
        timeline_events: 100,
        trace_spans: 200,
        logs: 500,
        failed_entries: 50,
        snapshots: 50,
      },
      BudgetValue::Compact => Self {
        string_chars: 600,
        raw_string_chars: 4_000,
        timeline_events: 15,
        trace_spans: 40,
        logs: 100,
        failed_entries: 10,
        snapshots: 10,
      },
    };

    if let Some(max_chars) = max_chars {
      let max_chars = max_chars.clamp(120, 20_000);
      budget.string_chars = budget.string_chars.min(max_chars);
      budget.raw_string_chars = budget.raw_string_chars.min(max_chars);
    }

    budget
  }
}

pub(crate) fn parse<T>(arguments: Value) -> Result<T, String>
where
  T: for<'de> Deserialize<'de>,
{
  serde_json::from_value(arguments).map_err(|error| format!("invalid tool arguments: {error}"))
}
