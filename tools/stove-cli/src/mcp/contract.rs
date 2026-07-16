#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) enum MethodName {
  Initialize,
  Ping,
  ToolsList,
  ToolsCall,
}

impl MethodName {
  pub(crate) fn from_str(value: &str) -> Option<Self> {
    match value {
      "initialize" => Some(Self::Initialize),
      "ping" => Some(Self::Ping),
      "tools/list" => Some(Self::ToolsList),
      "tools/call" => Some(Self::ToolsCall),
      _ => None,
    }
  }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) enum ToolName {
  Apps,
  Runs,
  Failures,
  FailureDetail,
  Timeline,
  Trace,
  Snapshot,
  Interactions,
  RawEvidence,
}

impl ToolName {
  pub(crate) const ALL: [Self; 9] = [
    Self::Apps,
    Self::Runs,
    Self::Failures,
    Self::FailureDetail,
    Self::Timeline,
    Self::Trace,
    Self::Snapshot,
    Self::Interactions,
    Self::RawEvidence,
  ];

  pub(crate) const fn as_str(self) -> &'static str {
    match self {
      Self::Apps => "stove_apps",
      Self::Runs => "stove_runs",
      Self::Failures => "stove_failures",
      Self::FailureDetail => "stove_failure_detail",
      Self::Timeline => "stove_timeline",
      Self::Trace => "stove_trace",
      Self::Snapshot => "stove_snapshot",
      Self::Interactions => "stove_interactions",
      Self::RawEvidence => "stove_raw_evidence",
    }
  }

  pub(crate) fn from_str(value: &str) -> Option<Self> {
    Self::ALL.into_iter().find(|tool| tool.as_str() == value)
  }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) enum ArgName {
  AppName,
  Budget,
  Focus,
  Id,
  JsonPointer,
  Kind,
  Limit,
  MaxChars,
  RunId,
  Status,
  System,
  TestId,
  TraceId,
  View,
}

impl ArgName {
  pub(crate) const fn as_str(self) -> &'static str {
    match self {
      Self::AppName => "app_name",
      Self::Budget => "budget",
      Self::Focus => "focus",
      Self::Id => "id",
      Self::JsonPointer => "json_pointer",
      Self::Kind => "kind",
      Self::Limit => "limit",
      Self::MaxChars => "max_chars",
      Self::RunId => "run_id",
      Self::Status => "status",
      Self::System => "system",
      Self::TestId => "test_id",
      Self::TraceId => "trace_id",
      Self::View => "view",
    }
  }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) enum BudgetValue {
  Tiny,
  Compact,
  Full,
}

impl BudgetValue {
  pub(crate) const ALL: [Self; 3] = [Self::Tiny, Self::Compact, Self::Full];

  pub(crate) const fn as_str(self) -> &'static str {
    match self {
      Self::Tiny => "tiny",
      Self::Compact => "compact",
      Self::Full => "full",
    }
  }

  pub(crate) fn from_str(value: &str) -> Option<Self> {
    Self::ALL
      .into_iter()
      .find(|budget| budget.as_str() == value)
  }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) enum TimelineFocus {
  Failure,
  All,
}

impl TimelineFocus {
  pub(crate) const ALL: [Self; 2] = [Self::Failure, Self::All];

  pub(crate) const fn as_str(self) -> &'static str {
    match self {
      Self::Failure => "failure",
      Self::All => "all",
    }
  }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) enum TraceView {
  CriticalPath,
  Exceptions,
  Tree,
}

impl TraceView {
  pub(crate) const ALL: [Self; 3] = [Self::CriticalPath, Self::Exceptions, Self::Tree];

  pub(crate) const fn as_str(self) -> &'static str {
    match self {
      Self::CriticalPath => "critical_path",
      Self::Exceptions => "exceptions",
      Self::Tree => "tree",
    }
  }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) enum RawEvidenceKind {
  Entry,
  Span,
  Snapshot,
  Interaction,
  Warning,
}

impl RawEvidenceKind {
  pub(crate) const ALL: [Self; 5] = [
    Self::Entry,
    Self::Span,
    Self::Snapshot,
    Self::Interaction,
    Self::Warning,
  ];

  pub(crate) const fn as_str(self) -> &'static str {
    match self {
      Self::Entry => "entry",
      Self::Span => "span",
      Self::Snapshot => "snapshot",
      Self::Interaction => "interaction",
      Self::Warning => "warning",
    }
  }

  pub(crate) fn from_str(value: &str) -> Option<Self> {
    Self::ALL.into_iter().find(|kind| kind.as_str() == value)
  }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) enum RunStatusValue {
  Running,
  Passed,
  Failed,
}

impl RunStatusValue {
  pub(crate) const ALL: [Self; 3] = [Self::Running, Self::Passed, Self::Failed];

  pub(crate) const fn as_str(self) -> &'static str {
    match self {
      Self::Running => "RUNNING",
      Self::Passed => "PASSED",
      Self::Failed => "FAILED",
    }
  }
}

pub(crate) const STATUS_ERROR: &str = "ERROR";
