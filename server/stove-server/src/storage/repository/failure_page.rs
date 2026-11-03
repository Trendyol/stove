use serde::{Deserialize, Serialize};

use super::{Backend, MAX_RUN_PAGE_SIZE, Repository, cursor};
use crate::error::{AppError, Result};
use crate::storage::models::{Run, Test};

#[derive(Debug, Default, Serialize)]
pub struct FailureFilter {
  pub app: Option<String>,
  pub run_id: Option<String>,
}

pub struct FailurePage {
  pub failures: Vec<(Run, Test)>,
  pub next_cursor: Option<String>,
}

#[derive(Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub(super) struct Position {
  pub run_started_at: String,
  pub run_id: String,
  pub test_started_at: String,
  pub test_id: String,
}

impl cursor::CursorPosition for Position {
  fn is_valid(&self) -> bool {
    !self.run_started_at.is_empty()
      && !self.run_id.is_empty()
      && !self.test_started_at.is_empty()
      && !self.test_id.is_empty()
  }
}

impl Repository {
  pub fn count_failures(&self, filter: &FailureFilter) -> Result<i64> {
    self.with_backend("count_failures", "read", |backend| match backend {
      Backend::Sqlite(sqlite) => sqlite.count_failures(filter),
      Backend::Postgres(postgres) => postgres.count_failures(filter),
    })
  }

  pub fn get_failure_page(
    &self,
    filter: &FailureFilter,
    limit: usize,
    token: Option<&str>,
  ) -> Result<FailurePage> {
    if !(1..=MAX_RUN_PAGE_SIZE).contains(&limit) {
      return Err(AppError::InvalidEvent(format!(
        "limit must be between 1 and {MAX_RUN_PAGE_SIZE}"
      )));
    }
    let position = token
      .map(|token| cursor::decode::<Position>(token, "failures", filter))
      .transpose()?;
    let fetch_limit = i64::try_from(limit + 1).expect("validated page size fits in i64");
    let mut failures = self.with_backend("get_failure_page", "read", |backend| match backend {
      Backend::Sqlite(sqlite) => sqlite.get_failure_page(filter, fetch_limit, position.as_ref()),
      Backend::Postgres(postgres) => {
        postgres.get_failure_page(filter, fetch_limit, position.as_ref())
      }
    })?;
    let has_more = failures.len() > limit;
    failures.truncate(limit);
    let next_cursor = if has_more {
      let (run, test) = failures.last().expect("nonempty page");
      Some(cursor::encode(
        "failures",
        filter,
        Position {
          run_started_at: run.started_at.clone(),
          run_id: run.id.clone(),
          test_started_at: test.started_at.clone(),
          test_id: test.id.clone(),
        },
      )?)
    } else {
      None
    };
    Ok(FailurePage {
      failures,
      next_cursor,
    })
  }
}

// Both engines share the same Diesel predicate. Counts and page reads must use
// identical selectors, including explicit run IDs and failed/error test status.
macro_rules! failure_query {
  ($filter:expr, $position:expr) => {{
    let filter = $filter;
    let mut query = runs::table
      .inner_join(tests::table.on(tests::run_id.eq(runs::id)))
      .filter(tests::status.eq_any(["FAILED", "ERROR"]))
      .into_boxed();
    if let Some(app) = &filter.app {
      query = query.filter(runs::app_name.eq(app));
    }
    if let Some(run_id) = &filter.run_id {
      query = query.filter(runs::id.eq(run_id));
    } else {
      query = query.filter(runs::status.eq_any(["FAILED", "RUNNING"]));
    }
    if let Some(position) = $position {
      // Newest runs first; within a run, tests are chronological with an ID tie-breaker.
      query = query.filter(
        runs::started_at
          .lt(&position.run_started_at)
          .or(
            runs::started_at
              .eq(&position.run_started_at)
              .and(runs::id.lt(&position.run_id)),
          )
          .or(
            runs::started_at
              .eq(&position.run_started_at)
              .and(runs::id.eq(&position.run_id))
              .and(
                tests::started_at.gt(&position.test_started_at).or(
                  tests::started_at
                    .eq(&position.test_started_at)
                    .and(tests::id.gt(&position.test_id)),
                ),
              ),
          ),
      );
    }
    query
  }};
}
pub(super) use failure_query;
