//! Decorate only tool-owned envelopes. Captured payloads are never traversed.
use crate::navigation::{base_path, reference};
use serde_json::{Value, json};

pub(super) fn attach(value: &mut Value, public_url: Option<&str>) {
  let prefix = base_path(public_url);
  attach_scoped(value, public_url, &prefix, None, None);
}

fn attach_scoped(
  value: &mut Value,
  public_url: Option<&str>,
  prefix: &str,
  inherited_run: Option<&str>,
  inherited_test: Option<&str>,
) {
  if let Some(items) = value.as_array_mut() {
    for item in items {
      attach_scoped(item, public_url, prefix, inherited_run, inherited_test);
    }
    return;
  }
  let Some(object) = value.as_object_mut() else {
    return;
  };
  let run = object
    .get("run_id")
    .and_then(Value::as_str)
    .or(inherited_run)
    .map(str::to_owned);
  let test = object
    .get("test_id")
    .and_then(Value::as_str)
    .or_else(|| {
      object
        .get("test")
        .and_then(|test| test.get("test_id"))
        .and_then(Value::as_str)
    })
    .or(inherited_test)
    .map(str::to_owned);
  if !object.contains_key("navigation")
    && let (Some(run), Some(test)) = (&run, &test)
    && (object.contains_key("test") || object.contains_key("test_name"))
  {
    object.insert("navigation".into(), json!(reference(run, Some(test), None)));
  }
  for key in ["navigation", "error_navigation"] {
    let Some(nav) = object.get_mut(key).filter(|value| value.is_object()) else {
      continue;
    };
    let nav_run = nav["run_id"].as_str().unwrap_or_default().to_owned();
    let mut path = nav["path"].as_str().unwrap_or_default().to_owned();
    // A span can be presented by trace alone or inside a proven test scope.
    if nav["test_id"].is_null()
      && run.as_deref() == Some(&nav_run)
      && test.is_some()
      && path.contains("focus=span:")
      && let Some((_, query)) = path.split_once('?')
    {
      path = format!(
        "{}?{query}",
        reference(&nav_run, test.as_deref(), None).path
      );
      nav["test_id"] = json!(test);
    }
    nav["url"] = json!(public_url.map(|base| format!("{base}{path}")));
    nav["path"] = json!(format!("{prefix}{path}"));
  }
  for key in [
    "groups",
    "failures",
    "test",
    "events",
    "failed_entries",
    "timeline_summary",
    "trace_summary",
    "trace",
    "critical_path",
    "spans",
    "failed_spans",
    "exception_spans",
    "exceptions",
    "snapshot_summaries",
    "snapshots",
    "unmatched_interactions",
    "mock_warnings",
    "warnings",
    "interactions",
    "raw_evidence",
    "evidence",
  ] {
    if let Some(child) = object.get_mut(key) {
      attach_scoped(child, public_url, prefix, run.as_deref(), test.as_deref());
    }
  }
}
