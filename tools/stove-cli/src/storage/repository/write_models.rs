use std::collections::BTreeMap;

use crate::ingest::PersistedDashboardEvent;
use crate::storage::models::{NewEntry, NewMockInteraction, NewMockWarning, NewSpan, RunStatus};

pub(super) struct RunStart<'a> {
  pub run_id: &'a str,
  pub app_name: &'a str,
  pub started_at: &'a str,
  pub stove_version: Option<&'a str>,
  pub systems: &'a [String],
  pub metadata: &'a BTreeMap<String, String>,
}

impl<'a> RunStart<'a> {
  pub fn new(
    run_id: &'a str,
    app_name: &'a str,
    started_at: &'a str,
    stove_version: Option<&'a str>,
    systems: &'a [String],
    metadata: &'a BTreeMap<String, String>,
  ) -> Self {
    Self {
      run_id,
      app_name,
      started_at,
      stove_version,
      systems,
      metadata,
    }
  }
}

pub(super) struct RunEnd<'a> {
  pub run_id: &'a str,
  pub ended_at: &'a str,
  pub total_tests: i32,
  pub passed: i32,
  pub failed: i32,
  pub duration_ms: i64,
}

impl<'a> RunEnd<'a> {
  pub fn new(
    run_id: &'a str,
    ended_at: &'a str,
    total_tests: i32,
    passed: i32,
    failed: i32,
    duration_ms: i64,
  ) -> Self {
    Self {
      run_id,
      ended_at,
      total_tests,
      passed,
      failed,
      duration_ms,
    }
  }

  pub fn status(&self) -> RunStatus {
    if self.failed > 0 {
      RunStatus::Failed
    } else {
      RunStatus::Passed
    }
  }
}

pub(super) struct TestStart<'a> {
  pub run_id: &'a str,
  pub test_id: &'a str,
  pub test_name: &'a str,
  pub spec_name: &'a str,
  pub test_path: &'a [String],
  pub started_at: &'a str,
}

impl<'a> TestStart<'a> {
  pub fn new(
    run_id: &'a str,
    test_id: &'a str,
    test_name: &'a str,
    spec_name: &'a str,
    test_path: &'a [String],
    started_at: &'a str,
  ) -> Self {
    Self {
      run_id,
      test_id,
      test_name,
      spec_name,
      test_path,
      started_at,
    }
  }
}

pub(super) struct TestEnd<'a> {
  pub run_id: &'a str,
  pub test_id: &'a str,
  pub status: &'a str,
  pub duration_ms: i64,
  pub error: &'a str,
  pub ended_at: &'a str,
}

impl<'a> TestEnd<'a> {
  pub fn new(
    run_id: &'a str,
    test_id: &'a str,
    status: &'a str,
    duration_ms: i64,
    error: &'a str,
    ended_at: &'a str,
  ) -> Self {
    Self {
      run_id,
      test_id,
      status,
      duration_ms,
      error,
      ended_at,
    }
  }
}

pub(super) struct SnapshotWrite<'a> {
  pub run_id: &'a str,
  pub test_id: &'a str,
  pub system: &'a str,
  pub state_json: &'a str,
  pub summary: &'a str,
  pub captured_at: &'a str,
  pub trigger: &'a str,
}

impl<'a> SnapshotWrite<'a> {
  pub fn new(
    run_id: &'a str,
    test_id: &'a str,
    system: &'a str,
    state_json: &'a str,
    summary: &'a str,
    captured_at: &'a str,
    trigger: &'a str,
  ) -> Self {
    Self {
      run_id,
      test_id,
      system,
      state_json,
      summary,
      captured_at,
      trigger,
    }
  }
}

pub(super) enum WriteOperation<'a> {
  RunStarted(RunStart<'a>),
  RunEnded(RunEnd<'a>),
  TestStarted(TestStart<'a>),
  TestEnded(TestEnd<'a>),
  Entry(&'a NewEntry),
  Span(&'a NewSpan),
  Snapshot(SnapshotWrite<'a>),
  MockInteraction(&'a NewMockInteraction),
  MockWarning(&'a NewMockWarning),
}

impl<'a> From<&'a PersistedDashboardEvent> for WriteOperation<'a> {
  fn from(event: &'a PersistedDashboardEvent) -> Self {
    match event {
      PersistedDashboardEvent::RunStarted {
        run_id,
        app_name,
        started_at,
        stove_version,
        systems,
        metadata,
      } => Self::RunStarted(RunStart::new(
        run_id,
        app_name,
        started_at,
        stove_version.as_deref(),
        systems,
        metadata,
      )),
      PersistedDashboardEvent::RunEnded {
        run_id,
        ended_at,
        total_tests,
        passed,
        failed,
        duration_ms,
      } => Self::RunEnded(RunEnd::new(
        run_id,
        ended_at,
        *total_tests,
        *passed,
        *failed,
        *duration_ms,
      )),
      PersistedDashboardEvent::TestStarted {
        run_id,
        test_id,
        test_name,
        spec_name,
        test_path,
        started_at,
      } => Self::TestStarted(TestStart::new(
        run_id, test_id, test_name, spec_name, test_path, started_at,
      )),
      PersistedDashboardEvent::TestEnded {
        run_id,
        test_id,
        status,
        duration_ms,
        error,
        ended_at,
      } => Self::TestEnded(TestEnd::new(
        run_id,
        test_id,
        status,
        *duration_ms,
        error.as_deref().unwrap_or_default(),
        ended_at,
      )),
      PersistedDashboardEvent::EntryRecorded(entry) => Self::Entry(entry),
      PersistedDashboardEvent::SpanRecorded(span) => Self::Span(span),
      PersistedDashboardEvent::Snapshot {
        run_id,
        test_id,
        system,
        state_json,
        summary,
        captured_at,
        trigger,
      } => Self::Snapshot(SnapshotWrite::new(
        run_id,
        test_id,
        system,
        state_json,
        summary,
        captured_at,
        trigger,
      )),
      PersistedDashboardEvent::MockInteraction(interaction) => Self::MockInteraction(interaction),
      PersistedDashboardEvent::MockWarning(warning) => Self::MockWarning(warning),
    }
  }
}
