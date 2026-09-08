use std::collections::{BTreeMap, HashMap};

use super::{Backend, Repository};
use crate::error::{AppError, Result};
use crate::storage::models::{PurgePreview, PurgeResult, StorageStats};

pub(super) struct PurgeCandidate {
  pub run_id: String,
  pub app_name: String,
  pub started_at: String,
  pub status: String,
  pub metadata: String,
}

impl From<(String, String, String, String, String)> for PurgeCandidate {
  fn from(
    (run_id, app_name, started_at, status, metadata): (String, String, String, String, String),
  ) -> Self {
    Self {
      run_id,
      app_name,
      started_at,
      status,
      metadata,
    }
  }
}

pub(super) fn select_purge_candidates(
  candidates: impl IntoIterator<Item = PurgeCandidate>,
  app_name: Option<&str>,
  older_than: Option<&str>,
  include_running: bool,
  metadata: &BTreeMap<String, Vec<String>>,
) -> Result<Vec<String>> {
  let mut selected = Vec::new();
  for candidate in candidates {
    if app_name.is_some_and(|expected| candidate.app_name != expected)
      || older_than.is_some_and(|cutoff| candidate.started_at.as_str() >= cutoff)
      || !is_purgeable(&candidate.status, include_running)
    {
      continue;
    }
    if !metadata.is_empty() {
      let values: BTreeMap<String, String> = serde_json::from_str(&candidate.metadata)
        .map_err(|_| AppError::InvalidStoredField("runs.metadata"))?;
      if !metadata.iter().all(|(key, accepted)| {
        values
          .get(key)
          .is_some_and(|value| accepted.contains(value))
      }) {
        continue;
      }
    }
    selected.push(candidate.run_id);
  }
  Ok(selected)
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
    self.with_backend(|backend| match backend {
      Backend::Sqlite(sqlite) => sqlite.storage_stats(self.retention_runs_per_app()),
      Backend::Postgres(postgres) => postgres.storage_stats(),
    })
  }

  pub fn update_retention(&self, runs_per_app: usize) -> Result<()> {
    self.with_backend(|backend| match backend {
      Backend::Sqlite(sqlite) => sqlite.update_retention(runs_per_app),
      Backend::Postgres(postgres) => postgres.update_retention(runs_per_app),
    })?;
    self.set_retention_runs_per_app(runs_per_app);
    Ok(())
  }

  pub fn preview_purge(
    &self,
    app_name: Option<&str>,
    older_than: Option<&str>,
    include_running: bool,
    metadata: &BTreeMap<String, Vec<String>>,
  ) -> Result<PurgePreview> {
    if !metadata.is_empty() && app_name.is_none_or(|name| name.trim().is_empty()) {
      return Err(AppError::InvalidEvent(
        "Select an application to purge by metadata".into(),
      ));
    }
    if metadata.values().any(Vec::is_empty) {
      return Err(AppError::InvalidEvent(
        "Select at least one value for each metadata field".into(),
      ));
    }
    self.with_backend(|backend| match backend {
      Backend::Sqlite(sqlite) => {
        sqlite.preview_purge(app_name, older_than, include_running, metadata)
      }
      Backend::Postgres(postgres) => {
        postgres.preview_purge(app_name, older_than, include_running, metadata)
      }
    })
  }

  pub fn purge_runs(&self, run_ids: &[String], include_running: bool) -> Result<PurgeResult> {
    self.with_backend(|backend| match backend {
      Backend::Sqlite(sqlite) => sqlite.purge_runs(run_ids, include_running),
      Backend::Postgres(postgres) => postgres.purge_runs(run_ids, include_running),
    })
  }
}
