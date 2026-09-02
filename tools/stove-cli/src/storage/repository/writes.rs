use std::collections::BTreeMap;

use super::write_models::{RunEnd, RunStart, SnapshotWrite, TestEnd, TestStart};
use super::{Backend, Repository, run_blocking};
use crate::error::Result;
use crate::storage::models::{NewEntry, NewMockInteraction, NewMockWarning, NewSpan};

impl Repository {
  pub fn save_run_start(
    &self,
    run_id: &str,
    app_name: &str,
    started_at: &str,
    systems: &[String],
  ) -> Result<()> {
    self.save_run_start_with_version(run_id, app_name, started_at, None, systems)
  }

  pub fn save_run_start_with_version(
    &self,
    run_id: &str,
    app_name: &str,
    started_at: &str,
    stove_version: Option<&str>,
    systems: &[String],
  ) -> Result<()> {
    self.save_run_start_with_metadata(
      run_id,
      app_name,
      started_at,
      stove_version,
      systems,
      &BTreeMap::new(),
    )
  }

  pub fn save_run_start_with_metadata(
    &self,
    run_id: &str,
    app_name: &str,
    started_at: &str,
    stove_version: Option<&str>,
    systems: &[String],
    metadata: &BTreeMap<String, String>,
  ) -> Result<()> {
    let run = RunStart::new(
      run_id,
      app_name,
      started_at,
      stove_version,
      systems,
      metadata,
    );
    match &self.backend {
      Backend::Sqlite(sqlite) => sqlite.save_run_start(&run),
      Backend::Postgres(postgres) => run_blocking(|| postgres.save_run_start(&run)),
    }
  }

  pub fn save_run_end(
    &self,
    run_id: &str,
    ended_at: &str,
    total_tests: i32,
    passed: i32,
    failed: i32,
    duration_ms: i64,
  ) -> Result<()> {
    let run = RunEnd::new(run_id, ended_at, total_tests, passed, failed, duration_ms);
    match &self.backend {
      Backend::Sqlite(sqlite) => sqlite.save_run_end(&run, self.retention_runs_per_app()),
      Backend::Postgres(postgres) => run_blocking(|| postgres.save_run_end(&run)),
    }
  }

  pub fn save_test_start(
    &self,
    run_id: &str,
    test_id: &str,
    test_name: &str,
    spec_name: &str,
    test_path: &[String],
    started_at: &str,
  ) -> Result<()> {
    let test = TestStart::new(run_id, test_id, test_name, spec_name, test_path, started_at);
    match &self.backend {
      Backend::Sqlite(sqlite) => sqlite.save_test_start(&test),
      Backend::Postgres(postgres) => run_blocking(|| postgres.save_test_start(&test)),
    }
  }

  pub fn save_test_end(
    &self,
    run_id: &str,
    test_id: &str,
    status: &str,
    duration_ms: i64,
    error: &str,
    ended_at: &str,
  ) -> Result<()> {
    let test = TestEnd::new(run_id, test_id, status, duration_ms, error, ended_at);
    match &self.backend {
      Backend::Sqlite(sqlite) => sqlite.save_test_end(&test),
      Backend::Postgres(postgres) => run_blocking(|| postgres.save_test_end(&test)),
    }
  }

  pub fn save_entry(&self, entry: &NewEntry) -> Result<()> {
    match &self.backend {
      Backend::Sqlite(sqlite) => sqlite.save_entry(entry),
      Backend::Postgres(postgres) => run_blocking(|| postgres.save_entry(entry)),
    }
  }

  pub fn save_span(&self, span: &NewSpan) -> Result<()> {
    match &self.backend {
      Backend::Sqlite(sqlite) => sqlite.save_span(span),
      Backend::Postgres(postgres) => run_blocking(|| postgres.save_span(span)),
    }
  }

  pub fn save_snapshot(
    &self,
    run_id: &str,
    test_id: &str,
    system: &str,
    state_json: &str,
    summary: &str,
  ) -> Result<()> {
    let snapshot = SnapshotWrite::new(run_id, test_id, system, state_json, summary, "", "TEST_END");
    match &self.backend {
      Backend::Sqlite(sqlite) => sqlite.save_snapshot(&snapshot),
      Backend::Postgres(postgres) => run_blocking(|| postgres.save_snapshot(&snapshot)),
    }
  }

  pub fn save_mock_interaction(&self, interaction: &NewMockInteraction) -> Result<()> {
    match &self.backend {
      Backend::Sqlite(sqlite) => sqlite.save_mock_interaction(interaction),
      Backend::Postgres(postgres) => run_blocking(|| postgres.save_mock_interaction(interaction)),
    }
  }

  pub fn save_mock_warning(&self, warning: &NewMockWarning) -> Result<()> {
    match &self.backend {
      Backend::Sqlite(sqlite) => sqlite.save_mock_warning(warning),
      Backend::Postgres(postgres) => run_blocking(|| postgres.save_mock_warning(warning)),
    }
  }

  pub fn clear_all(&self) -> Result<()> {
    match &self.backend {
      Backend::Sqlite(sqlite) => sqlite.clear_all(),
      Backend::Postgres(postgres) => run_blocking(|| postgres.clear_all()),
    }
  }
}
