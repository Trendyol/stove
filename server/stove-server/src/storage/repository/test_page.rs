use serde::{Deserialize, Serialize};
use utoipa::ToSchema;

use super::{Backend, Repository, cursor};
use crate::error::{AppError, Result};
use crate::storage::models::Test;

#[derive(Debug, Clone, Copy, Default, Serialize, Deserialize, ToSchema)]
#[serde(rename_all = "lowercase")]
pub enum TestOutcome {
  #[default]
  All,
  Pass,
  Fail,
}

#[derive(Debug, Default, Serialize)]
pub struct TestFilter {
  pub run_id: String,
  pub outcome: TestOutcome,
  pub search: String,
}

#[derive(Debug, Default, Serialize, ToSchema)]
pub struct TestSummary {
  pub total: i64,
  pub passed: i64,
  pub failed: i64,
  pub running: i64,
}

#[derive(Debug, Serialize, ToSchema)]
pub struct TestPage {
  pub tests: Vec<Test>,
  pub next_cursor: Option<String>,
  /// Matching tests across all pages.
  pub total: i64,
  pub remaining: i64,
  /// All tests in the run, independent of the active filters.
  pub summary: TestSummary,
}

#[derive(Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub(super) struct Position {
  pub started_at: String,
  pub id: String,
}

impl cursor::CursorPosition for Position {
  fn is_valid(&self) -> bool {
    !self.started_at.is_empty() && !self.id.is_empty()
  }
}

impl Repository {
  pub fn get_test_page(
    &self,
    filter: &TestFilter,
    limit: usize,
    token: Option<&str>,
  ) -> Result<TestPage> {
    if !(1..=100).contains(&limit) {
      return Err(AppError::InvalidEvent(
        "limit must be between 1 and 100".into(),
      ));
    }
    let position = token
      .map(|token| cursor::decode::<Position>(token, "tests", filter))
      .transpose()?;
    let fetch_limit = i64::try_from(limit + 1).expect("validated page size fits in i64");
    let (mut tests, total, remaining, counts) =
      self.with_backend("get_test_page", "read", |backend| match backend {
        Backend::Sqlite(sqlite) => {
          let tests = sqlite.get_test_page(filter, fetch_limit, position.as_ref())?;
          let total = sqlite.count_tests(filter, None)?;
          let remaining = if position.is_some() {
            sqlite.count_tests(filter, position.as_ref())?
          } else {
            total
          };
          Ok((
            tests,
            total,
            remaining,
            sqlite.test_summary(&filter.run_id)?,
          ))
        }
        Backend::Postgres(postgres) => {
          let tests = postgres.get_test_page(filter, fetch_limit, position.as_ref())?;
          let total = postgres.count_tests(filter, None)?;
          let remaining = if position.is_some() {
            postgres.count_tests(filter, position.as_ref())?
          } else {
            total
          };
          Ok((
            tests,
            total,
            remaining,
            postgres.test_summary(&filter.run_id)?,
          ))
        }
      })?;
    let mut summary = TestSummary::default();
    for (status, count) in counts {
      summary.total += count;
      match status.as_str() {
        "PASSED" => summary.passed += count,
        "FAILED" | "ERROR" => summary.failed += count,
        "RUNNING" => summary.running += count,
        _ => {}
      }
    }
    let has_more = tests.len() > limit;
    tests.truncate(limit);
    let next_cursor = if has_more {
      let test = tests.last().expect("nonempty page");
      Some(cursor::encode(
        "tests",
        filter,
        Position {
          started_at: test.started_at.clone(),
          id: test.id.clone(),
        },
      )?)
    } else {
      None
    };
    let remaining = remaining
      .saturating_sub(i64::try_from(tests.len()).expect("bounded page"))
      .max(0);
    Ok(TestPage {
      tests,
      next_cursor,
      total,
      remaining,
      summary,
    })
  }
}
