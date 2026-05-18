use serde_json::Value;
use serde_json::json;

use super::common::tool_args;
use super::common::tool_call;
use crate::mcp::contract::ArgName;
use crate::mcp::contract::RawEvidenceKind;
use crate::mcp::contract::ToolName;
use crate::storage::models::Entry;
use crate::storage::models::Snapshot;
use crate::storage::models::Span;

pub(super) fn entry_preview(entry: &Entry, max_chars: usize) -> Value {
  json!({
    "id": entry.id,
    "timestamp": entry.timestamp,
    "system": entry.system,
    "action": entry.action,
    "result": entry.result,
    "input": preview_field(entry.input.as_deref(), max_chars),
    "output": preview_field(entry.output.as_deref(), max_chars),
    "metadata": preview_field(entry.metadata.as_deref(), max_chars),
    "expected": preview_field(entry.expected.as_deref(), max_chars),
    "actual": preview_field(entry.actual.as_deref(), max_chars),
    "error": clip_opt(entry.error.as_deref(), max_chars),
    "trace_id": entry.trace_id,
    "raw_tool_call": tool_call(ToolName::RawEvidence, tool_args([
      (ArgName::Kind, json!(RawEvidenceKind::Entry.as_str())),
      (ArgName::Id, json!(entry.id)),
      (ArgName::RunId, json!(&entry.run_id)),
      (ArgName::TestId, json!(&entry.test_id)),
    ])),
  })
}

pub(super) fn span_preview(span: &Span, max_chars: usize) -> Value {
  json!({
    "id": span.id,
    "trace_id": span.trace_id,
    "span_id": span.span_id,
    "parent_span_id": span.parent_span_id,
    "operation_name": span.operation_name,
    "service_name": span.service_name,
    "duration_ms": nanos_to_millis(span.end_time_nanos - span.start_time_nanos),
    "status": span.status,
    "attributes": preview_field(span.attributes.as_deref(), max_chars),
    "exception_type": span.exception_type,
    "exception_message": clip_opt(span.exception_message.as_deref(), max_chars),
    "exception_stack_trace": clip_opt(span.exception_stack_trace.as_deref(), max_chars),
  })
}

pub(super) fn snapshot_summary(snapshot: &Snapshot, max_chars: usize) -> Value {
  let state = parse_state(&snapshot.state_json, max_chars);
  json!({
    "id": snapshot.id,
    "system": snapshot.system,
    "summary": clip_string(&snapshot.summary, max_chars),
    "state_overview": state_overview(&state),
    "snapshot_tool_call": tool_call(ToolName::Snapshot, tool_args([
      (ArgName::RunId, json!(&snapshot.run_id)),
      (ArgName::TestId, json!(&snapshot.test_id)),
      (ArgName::System, json!(&snapshot.system)),
    ])),
  })
}

pub(super) fn snapshot_detail(
  snapshot: &Snapshot,
  pointer: Option<&str>,
  max_chars: usize,
) -> Value {
  let parsed = parse_state(&snapshot.state_json, max_chars);
  let selected_state = pointer.map_or_else(
    || parsed.clone(),
    |pointer| {
      parsed
        .get("value")
        .and_then(|value| value.pointer(pointer))
        .map_or_else(
          || json!({ "parse_status": "pointer_not_found", "json_pointer": pointer }),
          |value| json!({ "parse_status": "ok", "json_pointer": pointer, "value": redact_value(value, max_chars) }),
        )
    },
  );

  json!({
    "id": snapshot.id,
    "system": snapshot.system,
    "summary": clip_string(&snapshot.summary, max_chars),
    "state": selected_state,
    "raw_tool_call": tool_call(ToolName::RawEvidence, tool_args([
      (ArgName::Kind, json!(RawEvidenceKind::Snapshot.as_str())),
      (ArgName::Id, json!(snapshot.id)),
      (ArgName::RunId, json!(&snapshot.run_id)),
      (ArgName::TestId, json!(&snapshot.test_id)),
    ])),
  })
}

fn state_overview(parsed: &Value) -> Value {
  let Some(value) = parsed.get("value") else {
    return parsed.clone();
  };
  match value {
    Value::Object(map) => json!({
      "type": "object",
      "keys": map.keys().take(20).collect::<Vec<_>>(),
      "key_count": map.len(),
    }),
    Value::Array(items) => json!({ "type": "array", "item_count": items.len() }),
    _ => json!({ "type": value_type(value), "value": value }),
  }
}

fn parse_state(raw: &str, max_chars: usize) -> Value {
  match serde_json::from_str::<Value>(raw) {
    Ok(value) => json!({ "parse_status": "ok", "value": redact_value(&value, max_chars) }),
    Err(error) => json!({
      "parse_status": "malformed_json",
      "parse_error": error.to_string(),
      "raw_preview": clip_string(raw, max_chars),
    }),
  }
}

fn preview_field(raw: Option<&str>, max_chars: usize) -> Value {
  let Some(raw) = raw.filter(|value| !value.is_empty()) else {
    return Value::Null;
  };

  match serde_json::from_str::<Value>(raw) {
    Ok(value) => redact_value(&value, max_chars),
    Err(error) => json!({
      "parse_status": "plain_or_malformed",
      "parse_error": error.to_string(),
      "preview": clip_string(raw, max_chars),
    }),
  }
}

fn redact_value(value: &Value, max_chars: usize) -> Value {
  match value {
    Value::Object(map) => Value::Object(
      map
        .iter()
        .map(|(key, value)| {
          if is_sensitive_key(key) {
            (key.clone(), Value::String("[REDACTED]".to_string()))
          } else {
            (key.clone(), redact_value(value, max_chars))
          }
        })
        .collect(),
    ),
    Value::Array(items) => Value::Array(
      items
        .iter()
        .take(50)
        .map(|item| redact_value(item, max_chars))
        .collect(),
    ),
    Value::String(value) => json!(clip_string(value, max_chars)),
    _ => value.clone(),
  }
}

fn is_sensitive_key(key: &str) -> bool {
  let lower = key.to_ascii_lowercase();
  [
    "authorization",
    "cookie",
    "password",
    "secret",
    "token",
    "apikey",
    "api_key",
    "credential",
  ]
  .iter()
  .any(|needle| lower.contains(needle))
}

pub(super) fn clip_opt(value: Option<&str>, max_chars: usize) -> Value {
  value
    .filter(|value| !value.is_empty())
    .map_or(Value::Null, |value| json!(clip_string(value, max_chars)))
}

fn clip_string(value: &str, max_chars: usize) -> String {
  let chars = value.chars().count();
  if chars <= max_chars {
    return value.to_string();
  }

  let prefix: String = value.chars().take(max_chars).collect();
  format!(
    "{prefix}...<truncated {} chars>",
    chars.saturating_sub(max_chars)
  )
}

#[allow(clippy::cast_precision_loss)]
fn nanos_to_millis(nanos: i64) -> f64 {
  nanos as f64 / 1_000_000.0
}

fn value_type(value: &Value) -> &'static str {
  match value {
    Value::Null => "null",
    Value::Bool(_) => "boolean",
    Value::Number(_) => "number",
    Value::String(_) => "string",
    Value::Array(_) => "array",
    Value::Object(_) => "object",
  }
}

#[cfg(test)]
mod tests {
  use super::*;

  #[test]
  fn redacts_sensitive_keys_recursively() {
    let value = json!({
      "Authorization": "Bearer secret",
      "nested": { "apiKey": "abc", "safe": "value" },
      "items": [{ "password": "pw" }]
    });

    let redacted = redact_value(&value, 100);

    assert_eq!(redacted["Authorization"], "[REDACTED]");
    assert_eq!(redacted["nested"]["apiKey"], "[REDACTED]");
    assert_eq!(redacted["nested"]["safe"], "value");
    assert_eq!(redacted["items"][0]["password"], "[REDACTED]");
  }

  #[test]
  fn clips_long_strings_deterministically() {
    let clipped = clip_string("abcdef", 3);

    assert_eq!(clipped, "abc...<truncated 3 chars>");
  }

  #[test]
  fn malformed_json_preview_keeps_parse_error() {
    let preview = preview_field(Some("{bad"), 20);

    assert_eq!(preview["parse_status"], "plain_or_malformed");
    assert!(preview["parse_error"].as_str().unwrap().contains("key"));
  }
}
