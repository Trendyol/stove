use serde_json::{Value, json};

use super::contract::{
  ArgName, BudgetValue, RawEvidenceKind, RunStatusValue, TimelineFocus, ToolName, TraceView,
};

pub(crate) fn definitions() -> Value {
  Value::Array(
    ToolName::ALL
      .into_iter()
      .map(ToolSpec::for_tool)
      .map(|spec| spec.to_json())
      .collect(),
  )
}

struct ToolSpec {
  tool: ToolName,
  description: &'static str,
  fields: Vec<FieldSpec>,
}

impl ToolSpec {
  fn for_tool(tool: ToolName) -> Self {
    match tool {
      ToolName::Apps => Self {
        tool,
        description: "List apps recorded in the Stove dashboard database. Use this when multiple apps may have test runs.",
        fields: list_fields(),
      },
      ToolName::Runs => Self {
        tool,
        description: "List Stove runs, optionally filtered by app_name and status. run_id is the canonical execution boundary for detail tools.",
        fields: vec![
          FieldSpec::string(ArgName::AppName).description("Optional app grouping label."),
          FieldSpec::string_enum(ArgName::Status, SchemaEnum::RunStatus),
          FieldSpec::limit(),
          FieldSpec::budget(),
          FieldSpec::max_chars(),
        ],
      },
      ToolName::Failures => Self {
        tool,
        description: "Default entrypoint for agents. Return failed or errored tests grouped by app and run, with ready-to-use detail tool calls.",
        fields: vec![
          FieldSpec::string(ArgName::AppName)
            .description("Optional app grouping label. Does not uniquely identify a run."),
          FieldSpec::string(ArgName::RunId).description("Optional exact run id."),
          FieldSpec::limit(),
          FieldSpec::budget(),
          FieldSpec::max_chars(),
        ],
      },
      ToolName::FailureDetail => Self {
        tool,
        description: "Return compact failure, timeline, trace, and snapshot summaries for one exact failed test.",
        fields: exact_test_fields(),
      },
      ToolName::Timeline => Self {
        tool,
        description: "Return ordered report entries for one exact test. Failure-focused by default.",
        fields: with_extra(
          exact_test_fields(),
          FieldSpec::string_enum(ArgName::Focus, SchemaEnum::TimelineFocus)
            .string_default(TimelineFocus::Failure.as_str()),
        ),
      },
      ToolName::Trace => Self {
        tool,
        description: "Return trace evidence by run_id + test_id or explicit trace_id. Multiple trace IDs are ranked with failed-entry traces first.",
        fields: vec![
          FieldSpec::string(ArgName::RunId),
          FieldSpec::string(ArgName::TestId),
          FieldSpec::string(ArgName::TraceId),
          FieldSpec::string_enum(ArgName::View, SchemaEnum::TraceView)
            .string_default(TraceView::CriticalPath.as_str()),
          FieldSpec::budget(),
          FieldSpec::max_chars(),
        ],
      },
      ToolName::Snapshot => Self {
        tool,
        description: "Return snapshot summaries and targeted state drill-down for one exact test.",
        fields: with_extra(
          with_extra(
            exact_test_fields(),
            FieldSpec::string(ArgName::System)
              .description("Optional system name such as Kafka or WireMock."),
          ),
          FieldSpec::string(ArgName::JsonPointer).description(
            "Optional RFC 6901 JSON pointer into snapshot state, for example /published/0.",
          ),
        ),
      },
      ToolName::RawEvidence => Self {
        tool,
        description: "Explicit capped raw evidence lookup by entry, span, or snapshot id. Prefer summary tools first.",
        fields: vec![
          FieldSpec::string_enum(ArgName::Kind, SchemaEnum::RawEvidenceKind).required(),
          FieldSpec::integer(ArgName::Id).required(),
          FieldSpec::string(ArgName::RunId),
          FieldSpec::string(ArgName::TestId),
          FieldSpec::string(ArgName::TraceId),
          FieldSpec::budget(),
          FieldSpec::max_chars(),
        ],
      },
    }
  }

  fn to_json(&self) -> Value {
    json!({
      "name": self.tool.as_str(),
      "description": self.description,
      "inputSchema": InputSchema::from_fields(&self.fields).to_json(),
    })
  }
}

struct InputSchema {
  fields: Vec<FieldSpec>,
}

impl InputSchema {
  fn from_fields(fields: &[FieldSpec]) -> Self {
    Self {
      fields: fields.to_vec(),
    }
  }

  fn to_json(&self) -> Value {
    let properties = Value::Object(self.fields.iter().map(FieldSpec::property_entry).collect());
    let required: Vec<&str> = self
      .fields
      .iter()
      .filter(|field| field.required)
      .map(|field| field.name.as_str())
      .collect();

    json!({
      "type": "object",
      "properties": properties,
      "required": required,
      "additionalProperties": false,
    })
  }
}

#[derive(Clone)]
struct FieldSpec {
  name: ArgName,
  kind: FieldKind,
  required: bool,
  description: Option<&'static str>,
  default: Option<Value>,
}

impl FieldSpec {
  fn string(name: ArgName) -> Self {
    Self::new(name, FieldKind::String)
  }

  fn string_enum(name: ArgName, enum_kind: SchemaEnum) -> Self {
    Self::new(name, FieldKind::StringEnum(enum_kind))
  }

  fn integer(name: ArgName) -> Self {
    Self::new(name, FieldKind::Integer)
  }

  fn limit() -> Self {
    Self::integer(ArgName::Limit)
      .minimum(1)
      .maximum(100)
      .integer_default(20)
  }

  fn budget() -> Self {
    Self::string_enum(ArgName::Budget, SchemaEnum::Budget)
      .string_default(BudgetValue::Compact.as_str())
  }

  fn max_chars() -> Self {
    Self::integer(ArgName::MaxChars)
      .minimum(120)
      .maximum(20_000)
      .description("Maximum characters for individual large evidence fields.")
  }

  fn new(name: ArgName, kind: FieldKind) -> Self {
    Self {
      name,
      kind,
      required: false,
      description: None,
      default: None,
    }
  }

  fn required(mut self) -> Self {
    self.required = true;
    self
  }

  fn description(mut self, description: &'static str) -> Self {
    self.description = Some(description);
    self
  }

  fn string_default(mut self, default: &'static str) -> Self {
    self.default = Some(json!(default));
    self
  }

  fn integer_default(mut self, default: i64) -> Self {
    self.default = Some(json!(default));
    self
  }

  fn minimum(mut self, minimum: i64) -> Self {
    if let FieldKind::Integer = &mut self.kind {
      self.kind = FieldKind::IntegerWithBounds {
        minimum: Some(minimum),
        maximum: None,
      };
    } else if let FieldKind::IntegerWithBounds { minimum: slot, .. } = &mut self.kind {
      *slot = Some(minimum);
    }
    self
  }

  fn maximum(mut self, maximum: i64) -> Self {
    if let FieldKind::Integer = &mut self.kind {
      self.kind = FieldKind::IntegerWithBounds {
        minimum: None,
        maximum: Some(maximum),
      };
    } else if let FieldKind::IntegerWithBounds { maximum: slot, .. } = &mut self.kind {
      *slot = Some(maximum);
    }
    self
  }

  fn property_entry(&self) -> (String, Value) {
    let mut property = match self.kind {
      FieldKind::String => json!({ "type": "string" }),
      FieldKind::StringEnum(enum_kind) => json!({
        "type": "string",
        "enum": enum_kind.values(),
      }),
      FieldKind::Integer => json!({ "type": "integer" }),
      FieldKind::IntegerWithBounds { minimum, maximum } => {
        let mut property = json!({ "type": "integer" });
        if let Some(minimum) = minimum {
          property["minimum"] = json!(minimum);
        }
        if let Some(maximum) = maximum {
          property["maximum"] = json!(maximum);
        }
        property
      }
    };

    if let Some(description) = self.description {
      property["description"] = json!(description);
    }
    if let Some(default) = &self.default {
      property["default"] = default.clone();
    }
    (self.name.as_str().to_string(), property)
  }
}

#[derive(Debug, Clone, Copy)]
enum FieldKind {
  String,
  StringEnum(SchemaEnum),
  Integer,
  IntegerWithBounds {
    minimum: Option<i64>,
    maximum: Option<i64>,
  },
}

#[derive(Debug, Clone, Copy)]
enum SchemaEnum {
  Budget,
  TimelineFocus,
  TraceView,
  RawEvidenceKind,
  RunStatus,
}

impl SchemaEnum {
  fn values(self) -> Vec<&'static str> {
    match self {
      Self::Budget => BudgetValue::ALL
        .into_iter()
        .map(BudgetValue::as_str)
        .collect(),
      Self::TimelineFocus => TimelineFocus::ALL
        .into_iter()
        .map(TimelineFocus::as_str)
        .collect(),
      Self::TraceView => TraceView::ALL.into_iter().map(TraceView::as_str).collect(),
      Self::RawEvidenceKind => RawEvidenceKind::ALL
        .into_iter()
        .map(RawEvidenceKind::as_str)
        .collect(),
      Self::RunStatus => RunStatusValue::ALL
        .into_iter()
        .map(RunStatusValue::as_str)
        .collect(),
    }
  }
}

fn list_fields() -> Vec<FieldSpec> {
  vec![
    FieldSpec::limit(),
    FieldSpec::budget(),
    FieldSpec::max_chars(),
  ]
}

fn exact_test_fields() -> Vec<FieldSpec> {
  vec![
    FieldSpec::string(ArgName::RunId)
      .required()
      .description("Exact Stove run id. This is the canonical execution boundary."),
    FieldSpec::string(ArgName::TestId)
      .required()
      .description("Exact Stove test id. Unique only within run_id."),
    FieldSpec::budget(),
    FieldSpec::max_chars(),
  ]
}

fn with_extra(mut fields: Vec<FieldSpec>, field: FieldSpec) -> Vec<FieldSpec> {
  fields.push(field);
  fields
}

#[cfg(test)]
mod tests {
  use super::*;

  #[test]
  fn definitions_include_one_schema_per_tool() {
    let definitions = definitions();
    let tools = definitions.as_array().unwrap();

    assert_eq!(tools.len(), ToolName::ALL.len());
    assert_eq!(tools[0]["name"], ToolName::Apps.as_str());
    assert_eq!(
      tools[ToolName::ALL.len() - 1]["name"],
      ToolName::RawEvidence.as_str()
    );
  }

  #[test]
  fn exact_test_tools_require_run_and_test_ids() {
    let detail = ToolSpec::for_tool(ToolName::FailureDetail).to_json();
    let required = detail["inputSchema"]["required"].as_array().unwrap();

    assert!(required.contains(&json!(ArgName::RunId.as_str())));
    assert!(required.contains(&json!(ArgName::TestId.as_str())));
  }

  #[test]
  fn typed_fields_preserve_defaults_and_enums() {
    let apps = ToolSpec::for_tool(ToolName::Apps).to_json();
    let limit = &apps["inputSchema"]["properties"][ArgName::Limit.as_str()];
    let budget = &apps["inputSchema"]["properties"][ArgName::Budget.as_str()];

    assert_eq!(limit["default"], 20);
    assert_eq!(budget["default"], BudgetValue::Compact.as_str());
    assert_eq!(
      budget["enum"],
      json!([
        BudgetValue::Tiny.as_str(),
        BudgetValue::Compact.as_str(),
        BudgetValue::Full.as_str()
      ])
    );
  }

  #[test]
  fn raw_evidence_schema_requires_kind_and_id() {
    let raw = ToolSpec::for_tool(ToolName::RawEvidence).to_json();
    let required = raw["inputSchema"]["required"].as_array().unwrap();
    let kind = &raw["inputSchema"]["properties"][ArgName::Kind.as_str()];

    assert!(required.contains(&json!(ArgName::Kind.as_str())));
    assert!(required.contains(&json!(ArgName::Id.as_str())));
    assert_eq!(
      kind["enum"],
      json!([
        RawEvidenceKind::Entry.as_str(),
        RawEvidenceKind::Span.as_str(),
        RawEvidenceKind::Snapshot.as_str()
      ])
    );
  }
}
