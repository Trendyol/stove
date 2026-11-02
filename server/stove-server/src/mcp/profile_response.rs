//! Profile-specific response budgeting, applied after analysis and navigation.
use super::protocol;
use serde_json::{Value, json};

pub(super) const MAX_BYTES: usize = 64 * 1024;

/// Budget the complete profile response, including baseline, navigation, escaped
/// text fallback and the JSON-RPC envelope, after all decoration is finished.
pub(super) fn tool_result(mut structured: Value, id: Option<&Value>) -> Value {
  loop {
    let result = protocol::tool_result(&structured);
    let envelope = protocol::rpc_envelope(id.cloned(), result.clone());
    if envelope.to_string().len() <= MAX_BYTES {
      return result;
    }
    let baseline_larger = structured.get("baseline").is_some_and(|baseline| {
      baseline["series"].to_string().len() > structured["series"].to_string().len()
    });
    structured["truncated"] = json!(true);
    let report = if baseline_larger {
      &mut structured["baseline"]
    } else {
      &mut structured
    };
    report["truncated"] = json!(true);
    let Some(series) = report["series"].as_array_mut().filter(|s| !s.is_empty()) else {
      return protocol::tool_error(
        "Profile report metadata exceeds the MCP response budget; narrow the selectors",
      );
    };
    let last = series.last_mut().unwrap();
    // Keep totals and source references for every series as long as possible.
    if last["hotspots"]
      .as_array_mut()
      .is_some_and(|a| a.pop().is_some())
    {
      continue;
    }
    if last["findings"]
      .as_array_mut()
      .is_some_and(|a| a.pop().is_some())
    {
      continue;
    }
    series.pop();
  }
}
