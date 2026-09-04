use std::collections::BTreeMap;

use super::{Backend, Repository};
use crate::error::Result;
use crate::storage::models::{
  AppSummary, Entry, MockInteraction, MockWarning, OpenAssertion, Run, Snapshot, Span, Test,
};

#[derive(Clone, Copy)]
pub(super) enum EvidenceScope<'a> {
  Run,
  Test(&'a str),
  Unattributed,
}

impl Repository {
  pub(crate) fn get_open_assertion(
    &self,
    run_id: &str,
    test_id: &str,
    correlation_key: &str,
  ) -> Result<Option<OpenAssertion>> {
    self.with_backend(|backend| match backend {
      Backend::Sqlite(sqlite) => sqlite.get_open_assertion(run_id, test_id, correlation_key),
      Backend::Postgres(postgres) => postgres.get_open_assertion(run_id, test_id, correlation_key),
    })
  }

  pub(crate) fn get_test_id_for_trace(
    &self,
    run_id: &str,
    trace_id: &str,
  ) -> Result<Option<String>> {
    self.with_backend(|backend| match backend {
      Backend::Sqlite(sqlite) => sqlite.get_test_id_for_trace(run_id, trace_id),
      Backend::Postgres(postgres) => postgres.get_test_id_for_trace(run_id, trace_id),
    })
  }

  pub fn get_apps(&self) -> Result<Vec<AppSummary>> {
    self.with_backend(|backend| match backend {
      Backend::Sqlite(sqlite) => sqlite.get_apps(),
      Backend::Postgres(postgres) => postgres.get_apps(),
    })
  }

  pub fn get_runs(&self, app_name: Option<&str>) -> Result<Vec<Run>> {
    self.get_runs_filtered(app_name, &BTreeMap::new())
  }

  pub fn get_runs_filtered(
    &self,
    app_name: Option<&str>,
    metadata: &BTreeMap<String, String>,
  ) -> Result<Vec<Run>> {
    self.with_backend(|backend| match backend {
      Backend::Sqlite(sqlite) => sqlite.get_runs_filtered(app_name, metadata),
      Backend::Postgres(postgres) => postgres.get_runs_filtered(app_name, metadata),
    })
  }

  pub fn get_run(&self, run_id: &str) -> Result<Option<Run>> {
    self.with_backend(|backend| match backend {
      Backend::Sqlite(sqlite) => sqlite.get_run(run_id),
      Backend::Postgres(postgres) => postgres.get_run(run_id),
    })
  }

  pub fn get_tests_for_run(&self, run_id: &str) -> Result<Vec<Test>> {
    self.with_backend(|backend| match backend {
      Backend::Sqlite(sqlite) => sqlite.get_tests_for_run(run_id),
      Backend::Postgres(postgres) => postgres.get_tests_for_run(run_id),
    })
  }

  pub fn get_entries(&self, run_id: &str, test_id: &str) -> Result<Vec<Entry>> {
    self.entries(run_id, test_id, false)
  }

  pub fn get_raw_entries(&self, run_id: &str, test_id: &str) -> Result<Vec<Entry>> {
    self.entries(run_id, test_id, true)
  }

  fn entries(&self, run_id: &str, test_id: &str, raw: bool) -> Result<Vec<Entry>> {
    self.with_backend(|backend| match backend {
      Backend::Sqlite(sqlite) => sqlite.get_entries(run_id, test_id, raw),
      Backend::Postgres(postgres) => postgres.get_entries(run_id, test_id, raw),
    })
  }

  pub fn get_spans_for_test(&self, run_id: &str, test_id: &str) -> Result<Vec<Span>> {
    self.with_backend(|backend| match backend {
      Backend::Sqlite(sqlite) => sqlite.get_spans_for_test(run_id, test_id),
      Backend::Postgres(postgres) => postgres.get_spans_for_test(run_id, test_id),
    })
  }

  pub fn get_trace(&self, trace_id: &str) -> Result<Vec<Span>> {
    self.with_backend(|backend| match backend {
      Backend::Sqlite(sqlite) => sqlite.get_trace(trace_id),
      Backend::Postgres(postgres) => postgres.get_trace(trace_id),
    })
  }

  pub fn get_snapshots(&self, run_id: &str, test_id: &str) -> Result<Vec<Snapshot>> {
    self.with_backend(|backend| match backend {
      Backend::Sqlite(sqlite) => sqlite.get_snapshots(run_id, test_id),
      Backend::Postgres(postgres) => postgres.get_snapshots(run_id, test_id),
    })
  }

  pub fn get_mock_interactions_for_test(
    &self,
    run_id: &str,
    test_id: &str,
  ) -> Result<Vec<MockInteraction>> {
    self.mock_interactions(run_id, EvidenceScope::Test(test_id))
  }

  pub fn get_mock_interactions_for_run(&self, run_id: &str) -> Result<Vec<MockInteraction>> {
    self.mock_interactions(run_id, EvidenceScope::Run)
  }

  pub fn get_unattributed_mock_interactions_for_run(
    &self,
    run_id: &str,
  ) -> Result<Vec<MockInteraction>> {
    self.mock_interactions(run_id, EvidenceScope::Unattributed)
  }

  fn mock_interactions(
    &self,
    run_id: &str,
    scope: EvidenceScope<'_>,
  ) -> Result<Vec<MockInteraction>> {
    self.with_backend(|backend| match backend {
      Backend::Sqlite(sqlite) => sqlite.get_mock_interactions(run_id, scope),
      Backend::Postgres(postgres) => postgres.get_mock_interactions(run_id, scope),
    })
  }

  pub fn get_mock_warnings_for_test(
    &self,
    run_id: &str,
    test_id: &str,
  ) -> Result<Vec<MockWarning>> {
    self.mock_warnings(run_id, EvidenceScope::Test(test_id))
  }

  pub fn get_mock_warnings_for_run(&self, run_id: &str) -> Result<Vec<MockWarning>> {
    self.mock_warnings(run_id, EvidenceScope::Run)
  }

  pub fn get_unattributed_mock_warnings_for_run(&self, run_id: &str) -> Result<Vec<MockWarning>> {
    self.mock_warnings(run_id, EvidenceScope::Unattributed)
  }

  fn mock_warnings(&self, run_id: &str, scope: EvidenceScope<'_>) -> Result<Vec<MockWarning>> {
    self.with_backend(|backend| match backend {
      Backend::Sqlite(sqlite) => sqlite.get_mock_warnings(run_id, scope),
      Backend::Postgres(postgres) => postgres.get_mock_warnings(run_id, scope),
    })
  }
}
