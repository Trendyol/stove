use std::collections::BTreeMap;

use serde::{Deserialize, Serialize};
use utoipa::ToSchema;

use super::{Backend, Repository, cursor};
use crate::error::{AppError, Result};
use crate::storage::models::{Run, RunStatus};

pub const DEFAULT_RUN_PAGE_SIZE: usize = 50;
pub const MAX_RUN_PAGE_SIZE: usize = 100;

#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize, Deserialize)]
pub struct RunFilter {
  pub app: Option<String>,
  pub status: Option<RunStatus>,
  pub metadata: MetadataSelectors,
}

/// Canonical OR-within-key, AND-across-keys selectors shared by REST and MCP.
#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize)]
#[serde(transparent)]
pub struct MetadataSelectors(BTreeMap<String, Vec<String>>);

impl MetadataSelectors {
  pub fn iter(&self) -> impl Iterator<Item = (&String, &Vec<String>)> {
    self.0.iter()
  }

  #[must_use]
  pub fn is_empty(&self) -> bool {
    self.0.is_empty()
  }
}

impl<'de> Deserialize<'de> for MetadataSelectors {
  fn deserialize<D: serde::Deserializer<'de>>(
    deserializer: D,
  ) -> std::result::Result<Self, D::Error> {
    #[derive(Deserialize)]
    #[serde(untagged)]
    enum Values {
      One(String),
      Any(Vec<String>),
    }
    let raw = BTreeMap::<String, Values>::deserialize(deserializer)?;
    let mut selectors = BTreeMap::new();
    for (key, values) in raw {
      let mut values = match values {
        Values::One(value) => vec![value],
        Values::Any(values) => values,
      };
      if values.is_empty() {
        return Err(serde::de::Error::custom(
          "metadata values must not be empty",
        ));
      }
      values.sort();
      values.dedup();
      selectors.insert(key, values);
    }
    Ok(Self(selectors))
  }
}

#[derive(Debug, Serialize, ToSchema)]
pub struct RunPage {
  pub runs: Vec<Run>,
  pub next_cursor: Option<String>,
}

#[derive(Debug, Serialize, ToSchema)]
pub struct RunMetadataFacet {
  pub key: String,
  pub values: Vec<RunMetadataValue>,
}

#[derive(Debug, Serialize, ToSchema)]
pub struct RunMetadataValue {
  pub value: String,
  pub count: i64,
}

#[allow(clippy::redundant_field_names)] // Diesel's generated initializer uses explicit field names.
mod facet_row {
  #[derive(diesel::QueryableByName)]
  pub(crate) struct FacetRow {
    #[diesel(sql_type = diesel::sql_types::Text)]
    pub key: String,
    #[diesel(sql_type = diesel::sql_types::Text)]
    pub value: String,
    #[diesel(sql_type = diesel::sql_types::BigInt)]
    pub count: i64,
  }
}
pub(super) use facet_row::FacetRow;

impl Repository {
  pub fn count_runs(&self, filter: &RunFilter) -> Result<i64> {
    self.with_backend("count_runs", "read", |backend| match backend {
      Backend::Sqlite(sqlite) => sqlite.count_runs(filter),
      Backend::Postgres(postgres) => postgres.count_runs(filter),
    })
  }

  pub fn failed_run_counts(&self, apps: &[String]) -> Result<BTreeMap<String, i64>> {
    self.with_backend("failed_run_counts", "read", |backend| match backend {
      Backend::Sqlite(sqlite) => sqlite.failed_run_counts(apps),
      Backend::Postgres(postgres) => postgres.failed_run_counts(apps),
    })
  }

  pub fn get_run_page(
    &self,
    filter: &RunFilter,
    limit: usize,
    cursor: Option<&str>,
  ) -> Result<RunPage> {
    if !(1..=MAX_RUN_PAGE_SIZE).contains(&limit) {
      return Err(AppError::InvalidEvent(format!(
        "limit must be between 1 and {MAX_RUN_PAGE_SIZE}"
      )));
    }
    let position = cursor
      .map(|token| cursor::decode::<Position>(token, "runs", filter))
      .transpose()?;
    let fetch_limit = i64::try_from(limit + 1).expect("validated page size fits in i64");
    let mut runs = self.with_backend("get_run_page", "read", |backend| match backend {
      Backend::Sqlite(sqlite) => sqlite.get_run_page(filter, fetch_limit, position.as_ref()),
      Backend::Postgres(postgres) => postgres.get_run_page(filter, fetch_limit, position.as_ref()),
    })?;
    let has_more = runs.len() > limit;
    runs.truncate(limit);
    let next_cursor = if has_more {
      let last = runs.last().expect("nonempty page");
      Some(cursor::encode(
        "runs",
        filter,
        Position {
          started_at: last.started_at.clone(),
          id: last.id.clone(),
        },
      )?)
    } else {
      None
    };
    Ok(RunPage { runs, next_cursor })
  }

  pub fn get_run_metadata_facets(
    &self,
    app: Option<&str>,
    scope: &BTreeMap<String, String>,
  ) -> Result<Vec<RunMetadataFacet>> {
    let rows = self.with_backend("get_run_metadata_facets", "read", |backend| match backend {
      Backend::Sqlite(sqlite) => sqlite.get_run_metadata_facets(app, scope),
      Backend::Postgres(postgres) => postgres.get_run_metadata_facets(app, scope),
    })?;
    let mut facets: BTreeMap<String, Vec<RunMetadataValue>> = BTreeMap::new();
    for row in rows {
      facets.entry(row.key).or_default().push(RunMetadataValue {
        value: row.value,
        count: row.count,
      });
    }
    Ok(
      facets
        .into_iter()
        .map(|(key, values)| RunMetadataFacet { key, values })
        .collect(),
    )
  }
}

#[derive(Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub(super) struct Position {
  pub started_at: String,
  pub id: String,
}

impl From<BTreeMap<String, String>> for MetadataSelectors {
  fn from(values: BTreeMap<String, String>) -> Self {
    Self(
      values
        .into_iter()
        .map(|(key, value)| (key, vec![value]))
        .collect(),
    )
  }
}

impl cursor::CursorPosition for Position {
  fn is_valid(&self) -> bool {
    !self.started_at.is_empty() && !self.id.is_empty()
  }
}
