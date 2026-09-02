use std::collections::BTreeMap;

use super::{Backend, Repository, run_blocking};
use crate::error::Result;
use crate::storage::models::{
  AppSummary, Entry, MockInteraction, MockWarning, Run, Snapshot, Span, Test,
};

impl Repository {
  pub fn get_apps(&self) -> Result<Vec<AppSummary>> {
    match &self.backend {
      Backend::Sqlite(sqlite) => sqlite.get_apps(),
      Backend::Postgres(postgres) => run_blocking(|| postgres.get_apps()),
    }
  }

  pub fn get_runs(&self, app_name: Option<&str>) -> Result<Vec<Run>> {
    self.get_runs_filtered(app_name, &BTreeMap::new())
  }

  pub fn get_runs_filtered(
    &self,
    app_name: Option<&str>,
    metadata: &BTreeMap<String, String>,
  ) -> Result<Vec<Run>> {
    match &self.backend {
      Backend::Sqlite(sqlite) => sqlite.get_runs_filtered(app_name, metadata),
      Backend::Postgres(postgres) => {
        run_blocking(|| postgres.get_runs_filtered(app_name, metadata))
      }
    }
  }

  pub fn get_run(&self, run_id: &str) -> Result<Option<Run>> {
    match &self.backend {
      Backend::Sqlite(sqlite) => sqlite.get_run(run_id),
      Backend::Postgres(postgres) => run_blocking(|| postgres.get_run(run_id)),
    }
  }

  pub fn get_tests_for_run(&self, run_id: &str) -> Result<Vec<Test>> {
    match &self.backend {
      Backend::Sqlite(sqlite) => sqlite.get_tests_for_run(run_id),
      Backend::Postgres(postgres) => run_blocking(|| postgres.get_tests_for_run(run_id)),
    }
  }

  pub fn get_entries(&self, run_id: &str, test_id: &str) -> Result<Vec<Entry>> {
    self.entries(run_id, test_id, false)
  }

  pub fn get_raw_entries(&self, run_id: &str, test_id: &str) -> Result<Vec<Entry>> {
    self.entries(run_id, test_id, true)
  }

  fn entries(&self, run_id: &str, test_id: &str, raw: bool) -> Result<Vec<Entry>> {
    match &self.backend {
      Backend::Sqlite(sqlite) => sqlite.get_entries(run_id, test_id, raw),
      Backend::Postgres(postgres) => run_blocking(|| postgres.get_entries(run_id, test_id, raw)),
    }
  }

  pub fn get_spans_for_test(&self, run_id: &str, test_id: &str) -> Result<Vec<Span>> {
    match &self.backend {
      Backend::Sqlite(sqlite) => sqlite.get_spans_for_test(run_id, test_id),
      Backend::Postgres(postgres) => run_blocking(|| postgres.get_spans_for_test(run_id, test_id)),
    }
  }

  pub fn get_trace(&self, trace_id: &str) -> Result<Vec<Span>> {
    match &self.backend {
      Backend::Sqlite(sqlite) => sqlite.get_trace(trace_id),
      Backend::Postgres(postgres) => run_blocking(|| postgres.get_trace(trace_id)),
    }
  }

  pub fn get_snapshots(&self, run_id: &str, test_id: &str) -> Result<Vec<Snapshot>> {
    match &self.backend {
      Backend::Sqlite(sqlite) => sqlite.get_snapshots(run_id, test_id),
      Backend::Postgres(postgres) => run_blocking(|| postgres.get_snapshots(run_id, test_id)),
    }
  }

  pub fn get_mock_interactions_for_test(
    &self,
    run_id: &str,
    test_id: &str,
  ) -> Result<Vec<MockInteraction>> {
    self.mock_interactions(run_id, Some(test_id), false)
  }

  pub fn get_mock_interactions_for_run(&self, run_id: &str) -> Result<Vec<MockInteraction>> {
    self.mock_interactions(run_id, None, false)
  }

  pub fn get_unattributed_mock_interactions_for_run(
    &self,
    run_id: &str,
  ) -> Result<Vec<MockInteraction>> {
    self.mock_interactions(run_id, None, true)
  }

  fn mock_interactions(
    &self,
    run_id: &str,
    test_id: Option<&str>,
    unattributed_only: bool,
  ) -> Result<Vec<MockInteraction>> {
    match &self.backend {
      Backend::Sqlite(sqlite) => sqlite.get_mock_interactions(run_id, test_id, unattributed_only),
      Backend::Postgres(postgres) => {
        run_blocking(|| postgres.get_mock_interactions(run_id, test_id, unattributed_only))
      }
    }
  }

  pub fn get_mock_warnings_for_test(
    &self,
    run_id: &str,
    test_id: &str,
  ) -> Result<Vec<MockWarning>> {
    self.mock_warnings(run_id, Some(test_id), false)
  }

  pub fn get_mock_warnings_for_run(&self, run_id: &str) -> Result<Vec<MockWarning>> {
    self.mock_warnings(run_id, None, false)
  }

  pub fn get_unattributed_mock_warnings_for_run(&self, run_id: &str) -> Result<Vec<MockWarning>> {
    self.mock_warnings(run_id, None, true)
  }

  fn mock_warnings(
    &self,
    run_id: &str,
    test_id: Option<&str>,
    unattributed_only: bool,
  ) -> Result<Vec<MockWarning>> {
    match &self.backend {
      Backend::Sqlite(sqlite) => sqlite.get_mock_warnings(run_id, test_id, unattributed_only),
      Backend::Postgres(postgres) => {
        run_blocking(|| postgres.get_mock_warnings(run_id, test_id, unattributed_only))
      }
    }
  }
}
