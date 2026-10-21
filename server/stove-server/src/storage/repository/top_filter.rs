use std::collections::{BTreeMap, BTreeSet};

use super::{
  Backend, Repository,
  mapping::{AppSummaryRow, TopFilterRow},
};
use crate::error::{AppError, Result};
use crate::storage::models::{AppSummary, TopFilterCatalog, TopFilterSettings, TopFilterValue};

pub(super) const CATALOG_RUNS_SQL: &str =
  "SELECT app_name, id AS latest_run_id, started_at AS latest_run_started_at,
          status AS latest_status, stove_version, CAST(metadata AS TEXT) AS metadata
   FROM runs ORDER BY started_at DESC, id DESC";

impl Repository {
  pub fn top_filter_catalog(&self) -> Result<TopFilterCatalog> {
    self.with_backend("top_filter_catalog", "read", |backend| match backend {
      Backend::Sqlite(sqlite) => sqlite.top_filter_catalog(),
      Backend::Postgres(postgres) => postgres.top_filter_catalog(),
    })
  }

  pub fn update_top_filter(&self, settings: &TopFilterSettings) -> Result<()> {
    if settings
      .metadata_key
      .as_ref()
      .is_some_and(|key| key.trim().is_empty())
    {
      return Err(AppError::InvalidEvent(
        "Select a metadata key or disable the top filter".into(),
      ));
    }
    self.with_backend("update_top_filter", "write", |backend| match backend {
      Backend::Sqlite(sqlite) => sqlite.update_top_filter(settings),
      Backend::Postgres(postgres) => postgres.update_top_filter(settings),
    })
  }
}

/// Rows arrive newest first, with the run ID breaking timestamp ties.
pub(super) fn catalog(
  settings: TopFilterRow,
  rows: Vec<AppSummaryRow>,
) -> Result<TopFilterCatalog> {
  let mut keys = BTreeSet::new();
  let mut groups: BTreeMap<String, BTreeMap<String, AppSummary>> = BTreeMap::new();
  for row in rows {
    let row = row.into_domain()?;
    keys.extend(row.metadata.keys().cloned());
    if let Some(value) = settings
      .top_filter_key
      .as_ref()
      .and_then(|key| row.metadata.get(key))
    {
      groups
        .entry(value.clone())
        .or_default()
        .entry(row.app_name.clone())
        .or_insert(row);
    }
  }
  Ok(TopFilterCatalog {
    settings: TopFilterSettings {
      metadata_key: settings.top_filter_key,
    },
    available_keys: keys.into_iter().collect(),
    values: groups
      .into_iter()
      .map(|(value, apps)| TopFilterValue {
        value,
        apps: apps.into_values().collect(),
      })
      .collect(),
  })
}
