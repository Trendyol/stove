use super::Analyzer;
use crate::profiling::{ProfileQuery, ProfileReport};
use serde_json::{Value, json};

const MAX_SUMMARY_BYTES: usize = 64 * 1024;
const MAX_SUMMARY_SERIES: usize = 8;
const MAX_SOURCE_PROFILES: usize = 32;

impl Analyzer {
  pub(super) fn profiles(&self, mut arguments: Value) -> Result<Value, String> {
    let run_id = arguments["run_id"]
      .as_str()
      .ok_or("run_id is required")?
      .to_string();
    let baseline = arguments["baseline_run_id"].as_str().map(str::to_owned);
    let fields = arguments
      .as_object_mut()
      .ok_or("arguments must be an object")?;
    fields.remove("run_id");
    fields.remove("baseline_run_id");
    let query: ProfileQuery = serde_json::from_value(arguments).map_err(|e| e.to_string())?;
    let mut value = self.profile_summary(&run_id, &query)?;
    if let Some(baseline) = baseline {
      if query.from_ms.is_some()
        || query.until_ms.is_some()
        || query.trace_id.is_some()
        || query.span_id.is_some()
        || query.instance.is_some()
      {
        return Err(
          "Baseline comparison requires run-wide selectors; use service and metric only".into(),
        );
      }
      let other = self.profile_summary(&baseline, &query)?;
      if value["app_name"] != other["app_name"] {
        return Err("Baseline must belong to the same application".into());
      }
      value["baseline"] = other;
      value["comparison_guidance"] = json!(
        "Compare sampling settings, window duration, process count and workload before interpreting differences. Totals are not normalized per operation; no regression is asserted."
      );
    }
    Ok(value)
  }
  pub(super) fn profile_summary(
    &self,
    run_id: &str,
    query: &ProfileQuery,
  ) -> Result<Value, String> {
    let mut report: ProfileReport =
      crate::profiling::report(&self.repository, run_id, query).map_err(|e| e.to_string())?;
    // Flame graph stack payloads are available through REST; MCP stays a compact evidence index.
    for series in &mut report.series {
      series.stacks.clear();
    }
    // Bound method text and source indexes as well as stack data for agent contexts.
    if report.series.len() > MAX_SUMMARY_SERIES {
      report.series.truncate(MAX_SUMMARY_SERIES);
      report.truncated = true;
    }
    for series in &mut report.series {
      if series.profile_ids.len() > MAX_SOURCE_PROFILES {
        series.profile_ids.truncate(MAX_SOURCE_PROFILES);
        report.truncated = true;
      }
    }
    while serde_json::to_vec(&report)
      .map_err(|e| e.to_string())?
      .len()
      > MAX_SUMMARY_BYTES
    {
      report.truncated = true;
      if report.series.len() > 1 {
        report.series.pop();
      } else if let Some(series) = report.series.first_mut() {
        if series.hotspots.pop().is_none() && series.findings.pop().is_none() {
          report.series.pop();
        }
      } else {
        return Err("Profile report metadata exceeds the MCP response budget".into());
      }
    }
    let mut value = serde_json::to_value(report).map_err(|e| e.to_string())?;
    value["detail_tool_call"] = json!({"name":"stove_profiles","arguments":{"run_id":run_id}});
    value["stack_detail"] = json!(
      "The run profiles REST endpoint contains the bounded flame graph stacks. profile_ids identify the source recording chunks."
    );
    Ok(value)
  }
}
