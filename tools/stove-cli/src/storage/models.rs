use std::fmt;
use std::str::FromStr;

use serde::Serialize;

/// Status of a test run.
#[derive(Debug, Clone, Serialize, PartialEq, Eq)]
pub enum RunStatus {
  #[serde(rename = "RUNNING")]
  Running,
  #[serde(rename = "PASSED")]
  Passed,
  #[serde(rename = "FAILED")]
  Failed,
}

impl FromStr for RunStatus {
  type Err = String;

  fn from_str(s: &str) -> Result<Self, Self::Err> {
    match s {
      "PASSED" => Ok(RunStatus::Passed),
      "FAILED" => Ok(RunStatus::Failed),
      "RUNNING" => Ok(RunStatus::Running),
      other => Err(format!("unknown run status: {other}")),
    }
  }
}

impl fmt::Display for RunStatus {
  fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
    match self {
      RunStatus::Running => write!(f, "RUNNING"),
      RunStatus::Passed => write!(f, "PASSED"),
      RunStatus::Failed => write!(f, "FAILED"),
    }
  }
}

/// Status of an individual test or entry result.
#[derive(Debug, Clone, Serialize, PartialEq, Eq)]
pub enum TestStatus {
  #[serde(rename = "RUNNING")]
  Running,
  #[serde(rename = "PASSED")]
  Passed,
  #[serde(rename = "FAILED")]
  Failed,
  #[serde(rename = "ERROR")]
  Error,
}

impl FromStr for TestStatus {
  type Err = String;

  fn from_str(s: &str) -> Result<Self, Self::Err> {
    match s {
      "PASSED" => Ok(TestStatus::Passed),
      "FAILED" => Ok(TestStatus::Failed),
      "ERROR" => Ok(TestStatus::Error),
      "RUNNING" => Ok(TestStatus::Running),
      other => Err(format!("unknown test status: {other}")),
    }
  }
}

impl fmt::Display for TestStatus {
  fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
    match self {
      TestStatus::Running => write!(f, "RUNNING"),
      TestStatus::Passed => write!(f, "PASSED"),
      TestStatus::Failed => write!(f, "FAILED"),
      TestStatus::Error => write!(f, "ERROR"),
    }
  }
}

/// Summary of an application known to the dashboard.
#[derive(Debug, Clone, Serialize, PartialEq, Eq)]
pub struct AppSummary {
  pub app_name: String,
  pub latest_run_id: String,
  pub latest_status: RunStatus,
  pub stove_version: Option<String>,
  pub total_runs: i32,
}

/// A single test run (one execution of a test suite).
#[derive(Debug, Clone, Serialize, PartialEq, Eq)]
pub struct Run {
  pub id: String,
  pub app_name: String,
  pub started_at: String,
  pub ended_at: Option<String>,
  pub status: RunStatus,
  pub total_tests: i32,
  pub passed: i32,
  pub failed: i32,
  pub duration_ms: Option<i64>,
  pub stove_version: Option<String>,
  pub systems: Vec<String>,
}

/// A single test within a run.
#[derive(Debug, Clone, Serialize, PartialEq, Eq)]
pub struct Test {
  pub id: String,
  pub run_id: String,
  pub test_name: String,
  pub spec_name: String,
  pub test_path: Vec<String>,
  pub started_at: String,
  pub ended_at: Option<String>,
  pub status: TestStatus,
  pub duration_ms: Option<i64>,
  pub error: Option<String>,
}

/// A report entry (action + result) within a test.
#[derive(Debug, Clone, Serialize, PartialEq, Eq)]
pub struct Entry {
  pub id: i64,
  pub run_id: String,
  pub test_id: String,
  pub timestamp: String,
  pub system: String,
  pub action: String,
  pub result: TestStatus,
  pub input: Option<String>,
  pub output: Option<String>,
  pub metadata: Option<String>,
  pub expected: Option<String>,
  pub actual: Option<String>,
  pub error: Option<String>,
  pub trace_id: Option<String>,
}

/// A span in a distributed trace.
#[derive(Debug, Clone, Serialize, PartialEq, Eq)]
pub struct Span {
  pub id: i64,
  pub run_id: String,
  pub trace_id: String,
  pub span_id: String,
  pub parent_span_id: Option<String>,
  pub operation_name: String,
  pub service_name: String,
  pub start_time_nanos: i64,
  pub end_time_nanos: i64,
  pub status: String,
  pub attributes: Option<String>,
  pub exception_type: Option<String>,
  pub exception_message: Option<String>,
  pub exception_stack_trace: Option<String>,
}

/// A system snapshot captured during a test.
#[derive(Debug, Clone, Serialize, PartialEq, Eq)]
pub struct Snapshot {
  pub id: i64,
  pub run_id: String,
  pub test_id: String,
  pub system: String,
  pub state_json: String,
  pub summary: String,
  pub captured_at: Option<String>,
  /// `TEST_END` for the regular end-of-test snapshot, `FAILURE` for the state
  /// captured at the moment the first failing entry was recorded.
  pub trigger: String,
}

/// One completed exchange observed by a mock system (`WireMock` / gRPC Mock).
/// `test_id` is `None` for unattributed evidence — attribution is proven-only,
/// so those render in a run-level lane instead of being guessed into a test.
#[derive(Debug, Clone, Serialize, PartialEq, Eq)]
pub struct MockInteraction {
  pub id: i64,
  pub run_id: String,
  pub test_id: Option<String>,
  pub timestamp: String,
  pub system: String,
  pub protocol: String,
  pub method: String,
  pub target: String,
  pub matched: bool,
  pub stub_id: Option<String>,
  pub attribution: String,
  pub request_body: Option<String>,
  pub request_body_truncated: bool,
  pub response_body: Option<String>,
  pub response_body_truncated: bool,
  pub status: String,
  pub latency_ms: Option<i64>,
  /// JSON array of rendered near-miss candidates; present for unmatched exchanges.
  pub near_misses: Option<String>,
  pub trace_id: Option<String>,
}

/// A diagnostic a mock system observed — never a test failure.
#[derive(Debug, Clone, Serialize, PartialEq, Eq)]
pub struct MockWarning {
  pub id: i64,
  pub run_id: String,
  pub test_id: Option<String>,
  pub timestamp: String,
  pub system: String,
  pub kind: String,
  pub message: String,
  pub stub_id: Option<String>,
  pub target: Option<String>,
}

// --- Input structs for write operations ---

/// Data required to save a new report entry.
#[derive(Clone, Debug)]
pub struct NewEntry {
  pub run_id: String,
  pub test_id: String,
  pub timestamp: String,
  pub system: String,
  pub action: String,
  pub result: String,
  pub input: String,
  pub output: String,
  pub metadata: String,
  pub expected: String,
  pub actual: String,
  pub error: String,
  pub trace_id: String,
}

/// Data required to save a new mock interaction.
#[derive(Clone, Debug, Default)]
pub struct NewMockInteraction {
  pub run_id: String,
  pub test_id: Option<String>,
  pub timestamp: String,
  pub system: String,
  pub protocol: String,
  pub method: String,
  pub target: String,
  pub matched: bool,
  pub stub_id: Option<String>,
  pub attribution: String,
  pub request_body: String,
  pub request_body_truncated: bool,
  pub response_body: String,
  pub response_body_truncated: bool,
  pub status: String,
  pub latency_ms: Option<i64>,
  /// JSON array of rendered near-miss candidates.
  pub near_misses: String,
  pub trace_id: Option<String>,
}

/// Data required to save a new mock warning.
#[derive(Clone, Debug, Default)]
pub struct NewMockWarning {
  pub run_id: String,
  pub test_id: Option<String>,
  pub timestamp: String,
  pub system: String,
  pub kind: String,
  pub message: String,
  pub stub_id: Option<String>,
  pub target: Option<String>,
}

/// Data required to save a new span.
#[derive(Clone, Debug, Default)]
pub struct NewSpan {
  pub run_id: String,
  pub trace_id: String,
  pub span_id: String,
  pub parent_span_id: String,
  pub operation_name: String,
  pub service_name: String,
  pub start_time_nanos: i64,
  pub end_time_nanos: i64,
  pub status: String,
  pub attributes: String,
  pub exception_type: String,
  pub exception_message: String,
  pub exception_stack_trace: String,
}
