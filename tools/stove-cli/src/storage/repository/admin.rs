use std::collections::HashMap;

use super::{Backend, Repository, run_blocking};
use crate::error::Result;
use crate::storage::models::{PurgePreview, PurgeResult, StorageStats};

pub(super) struct PurgeCandidate {
  pub run_id: String,
  pub app_name: String,
  pub started_at: String,
  pub status: String,
}

impl From<(String, String, String, String)> for PurgeCandidate {
  fn from((run_id, app_name, started_at, status): (String, String, String, String)) -> Self {
    Self {
      run_id,
      app_name,
      started_at,
      status,
    }
  }
}

pub(super) fn select_purge_candidates(
  candidates: impl IntoIterator<Item = PurgeCandidate>,
  app_name: Option<&str>,
  older_than: Option<&str>,
  include_running: bool,
) -> Vec<String> {
  candidates
    .into_iter()
    .filter(|candidate| {
      app_name.is_none_or(|expected| candidate.app_name == expected)
        && older_than.is_none_or(|cutoff| candidate.started_at.as_str() < cutoff)
        && is_purgeable(&candidate.status, include_running)
    })
    .map(|candidate| candidate.run_id)
    .collect()
}

pub(super) fn is_purgeable(status: &str, include_running: bool) -> bool {
  include_running || status != "RUNNING"
}

pub(super) fn select_requested_run_ids(
  requested: &[String],
  available: impl IntoIterator<Item = (String, String)>,
  include_running: bool,
) -> Vec<String> {
  let available = available.into_iter().collect::<HashMap<_, _>>();
  requested
    .iter()
    .filter(|run_id| {
      available
        .get(*run_id)
        .is_some_and(|status| is_purgeable(status, include_running))
    })
    .cloned()
    .collect()
}

impl Repository {
  pub fn storage_stats(&self) -> Result<StorageStats> {
    match &self.backend {
      Backend::Sqlite(sqlite) => sqlite.storage_stats(self.retention_runs_per_app()),
      Backend::Postgres(postgres) => run_blocking(|| postgres.storage_stats()),
    }
  }

  pub fn update_retention(&self, runs_per_app: usize) -> Result<()> {
    match &self.backend {
      Backend::Sqlite(sqlite) => sqlite.update_retention(runs_per_app)?,
      Backend::Postgres(postgres) => run_blocking(|| postgres.update_retention(runs_per_app))?,
    }
    self.set_retention_runs_per_app(runs_per_app);
    Ok(())
  }

  pub fn preview_purge(
    &self,
    app_name: Option<&str>,
    older_than: Option<&str>,
    include_running: bool,
  ) -> Result<PurgePreview> {
    match &self.backend {
      Backend::Sqlite(sqlite) => sqlite.preview_purge(app_name, older_than, include_running),
      Backend::Postgres(postgres) => {
        run_blocking(|| postgres.preview_purge(app_name, older_than, include_running))
      }
    }
  }

  pub fn purge_runs(&self, run_ids: &[String], include_running: bool) -> Result<PurgeResult> {
    match &self.backend {
      Backend::Sqlite(sqlite) => sqlite.purge_runs(run_ids, include_running),
      Backend::Postgres(postgres) => run_blocking(|| postgres.purge_runs(run_ids, include_running)),
    }
  }
}
