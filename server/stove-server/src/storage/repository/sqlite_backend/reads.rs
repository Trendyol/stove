use std::collections::BTreeMap;

use diesel::prelude::*;
use diesel::sql_types::Text;

use super::SqliteBackend;
use crate::error::Result;
use crate::storage::models::{
  AppSummary, Entry, MockInteraction, MockWarning, OpenAssertion, Run, Snapshot, Span, Test,
};
use crate::storage::repository::mapping::{
  AppSummaryRow, EntryRow, MockInteractionRow, MockWarningRow, OpenAssertionRow, RunRow,
  SnapshotRow, SpanRow, TestRow,
};
use crate::storage::repository::reads::EvidenceScope;
use crate::storage::schema::sqlite::{
  entries, mock_interactions, mock_warnings, runs, snapshots, spans, tests,
};

impl SqliteBackend {
  pub fn get_run_metadata_facets(
    &self,
    app: Option<&str>,
    scope: &BTreeMap<String, String>,
  ) -> Result<Vec<crate::storage::repository::run_page::FacetRow>> {
    let mut db = self.lock_read();
    Ok(
      diesel::sql_query(
        r"
      WITH params(app, scope) AS (VALUES (?, ?))
      SELECT facet.key AS key, facet.value AS value, count(*) AS count
      FROM runs, params, json_each(runs.metadata) facet
      WHERE (params.app IS NULL OR runs.app_name = params.app)
        AND facet.type = 'text'
        AND NOT EXISTS (
          SELECT 1 FROM json_each(params.scope) wanted
          WHERE NOT EXISTS (
            SELECT 1 FROM json_each(runs.metadata) actual
            WHERE actual.key = wanted.key
              AND actual.type = 'text'
              AND actual.value = wanted.value
          )
        )
      GROUP BY facet.key, facet.value
      ORDER BY facet.key, facet.value
    ",
      )
      .bind::<diesel::sql_types::Nullable<Text>, _>(app)
      .bind::<Text, _>(serde_json::to_string(scope)?)
      .load(db.conn())?,
    )
  }

  pub fn get_test(&self, run_id: &str, test_id: &str) -> Result<Option<Test>> {
    let mut db = self.lock_read();
    tests::table
      .filter(tests::run_id.eq(run_id))
      .filter(tests::id.eq(test_id))
      .select(tests::all_columns)
      .first::<TestRow>(db.conn())
      .optional()?
      .map(Test::try_from)
      .transpose()
  }

  pub fn get_entry(&self, run_id: &str, test_id: &str, id: i64) -> Result<Option<Entry>> {
    let mut db = self.lock_read();
    diesel::sql_query(&*ENTRY_BY_ID_SQL)
      .bind::<Text, _>(run_id)
      .bind::<Text, _>(test_id)
      .bind::<diesel::sql_types::BigInt, _>(id)
      .get_result::<EntryRow>(db.conn())
      .optional()?
      .map(Entry::try_from)
      .transpose()
  }

  pub fn entry_context(
    &self,
    run_id: &str,
    test_id: &str,
    timestamp: &str,
    id: i64,
    before: bool,
    limit: i64,
  ) -> Result<Vec<Entry>> {
    let mut db = self.lock_read();
    let sql = if before {
      &*ENTRIES_BEFORE_SQL
    } else {
      &*ENTRIES_AFTER_SQL
    };
    diesel::sql_query(sql)
      .bind::<Text, _>(run_id)
      .bind::<Text, _>(test_id)
      .bind::<Text, _>(timestamp)
      .bind::<diesel::sql_types::BigInt, _>(id)
      .bind::<diesel::sql_types::BigInt, _>(limit)
      .load::<EntryRow>(db.conn())?
      .into_iter()
      .map(Entry::try_from)
      .collect()
  }

  pub fn get_trace_span(&self, trace_id: &str, id: i64) -> Result<Option<Span>> {
    let mut db = self.lock_read();
    spans::table
      .filter(spans::trace_id.eq(trace_id))
      .filter(spans::id.eq(id))
      .select(spans::all_columns)
      .first::<SpanRow>(db.conn())
      .optional()?
      .map(Span::try_from)
      .transpose()
  }

  pub fn get_span(&self, run_id: &str, id: i64) -> Result<Option<Span>> {
    let mut db = self.lock_read();
    spans::table
      .filter(spans::run_id.eq(run_id))
      .filter(spans::id.eq(id))
      .select(spans::all_columns)
      .first::<SpanRow>(db.conn())
      .optional()?
      .map(Span::try_from)
      .transpose()
  }

  pub fn get_test_span(&self, run_id: &str, test_id: &str, id: i64) -> Result<Option<Span>> {
    let mut db = self.lock_read();
    diesel::sql_query(&*TEST_SPAN_BY_ID_SQL)
      .bind::<Text, _>(run_id)
      .bind::<Text, _>(test_id)
      .bind::<diesel::sql_types::BigInt, _>(id)
      .get_result::<SpanRow>(db.conn())
      .optional()?
      .map(Span::try_from)
      .transpose()
  }

  pub fn get_span_ancestors(
    &self,
    run_id: &str,
    trace_id: &str,
    id: i64,
    depth: i64,
  ) -> Result<Vec<Span>> {
    let mut db = self.lock_read();
    diesel::sql_query(crate::storage::repository::evidence_sql::SPAN_ANCESTORS)
      .bind::<Text, _>(run_id)
      .bind::<Text, _>(trace_id)
      .bind::<diesel::sql_types::BigInt, _>(id)
      .bind::<diesel::sql_types::BigInt, _>(depth)
      .load::<SpanRow>(db.conn())?
      .into_iter()
      .map(Span::try_from)
      .collect()
  }

  pub fn get_snapshot(&self, run_id: &str, test_id: &str, id: i64) -> Result<Option<Snapshot>> {
    let mut db = self.lock_read();
    Ok(
      snapshots::table
        .filter(snapshots::run_id.eq(run_id))
        .filter(snapshots::test_id.eq(test_id))
        .filter(snapshots::id.eq(id))
        .select(snapshots::all_columns)
        .first::<SnapshotRow>(db.conn())
        .optional()?
        .map(Snapshot::from),
    )
  }

  pub fn get_mock_interaction(&self, run_id: &str, id: i64) -> Result<Option<MockInteraction>> {
    let mut db = self.lock_read();
    mock_interactions::table
      .filter(mock_interactions::run_id.eq(run_id))
      .filter(mock_interactions::id.eq(id))
      .select(mock_interactions::all_columns)
      .first::<MockInteractionRow>(db.conn())
      .optional()?
      .map(|row| row.into_domain())
      .transpose()
  }

  pub fn get_mock_warning(&self, run_id: &str, id: i64) -> Result<Option<MockWarning>> {
    let mut db = self.lock_read();
    Ok(
      mock_warnings::table
        .filter(mock_warnings::run_id.eq(run_id))
        .filter(mock_warnings::id.eq(id))
        .select(mock_warnings::all_columns)
        .first::<MockWarningRow>(db.conn())
        .optional()?
        .map(MockWarning::from),
    )
  }

  pub fn related_interactions(
    &self,
    run_id: &str,
    test_id: Option<&str>,
    stub_id: &str,
    limit: i64,
  ) -> Result<Vec<MockInteraction>> {
    let mut db = self.lock_read();
    let mut query = mock_interactions::table
      .filter(mock_interactions::run_id.eq(run_id))
      .filter(mock_interactions::stub_id.eq(stub_id))
      .into_boxed();
    query = if let Some(test_id) = test_id {
      query.filter(mock_interactions::test_id.eq(test_id))
    } else {
      query.filter(mock_interactions::test_id.is_null())
    };
    query
      .order(mock_interactions::id)
      .limit(limit)
      .select(mock_interactions::all_columns)
      .load::<MockInteractionRow>(db.conn())?
      .into_iter()
      .map(|row| row.into_domain())
      .collect()
  }

  pub fn related_warnings(
    &self,
    run_id: &str,
    test_id: Option<&str>,
    stub_id: &str,
    limit: i64,
  ) -> Result<Vec<MockWarning>> {
    let mut db = self.lock_read();
    let mut query = mock_warnings::table
      .filter(mock_warnings::run_id.eq(run_id))
      .filter(mock_warnings::stub_id.eq(stub_id))
      .into_boxed();
    query = if let Some(test_id) = test_id {
      query.filter(mock_warnings::test_id.eq(test_id))
    } else {
      query.filter(mock_warnings::test_id.is_null())
    };
    Ok(
      query
        .order(mock_warnings::id)
        .limit(limit)
        .select(mock_warnings::all_columns)
        .load::<MockWarningRow>(db.conn())?
        .into_iter()
        .map(MockWarning::from)
        .collect(),
    )
  }

  pub fn get_open_assertion(
    &self,
    run_id: &str,
    test_id: &str,
    correlation_key: &str,
  ) -> Result<Option<OpenAssertion>> {
    let mut db = self.lock_read();
    let row = diesel::sql_query(
      "WITH latest AS (
         SELECT assertion_id, result FROM entries
          WHERE run_id = ? AND test_id = ? AND correlation_key = ?
          ORDER BY id DESC LIMIT 1
       )
       SELECT latest.assertion_id AS assertion_id, COUNT(entries.id) AS attempt_count,
              SUM(CASE WHEN entries.result IN ('FAILED', 'ERROR') THEN 1 ELSE 0 END) AS failure_count
         FROM latest JOIN entries ON entries.run_id = ? AND entries.test_id = ?
          AND entries.assertion_id = latest.assertion_id
        WHERE latest.result IN ('FAILED', 'ERROR') GROUP BY latest.assertion_id",
    )
    .bind::<Text, _>(run_id)
    .bind::<Text, _>(test_id)
    .bind::<Text, _>(correlation_key)
    .bind::<Text, _>(run_id)
    .bind::<Text, _>(test_id)
    .get_result::<OpenAssertionRow>(db.conn())
    .optional()?;
    Ok(row.map(OpenAssertion::from))
  }

  pub fn get_test_id_for_trace(&self, run_id: &str, trace_id: &str) -> Result<Option<String>> {
    let mut db = self.lock_read();
    Ok(
      entries::table
        .filter(entries::run_id.eq(run_id))
        .filter(entries::trace_id.eq(trace_id))
        .order(entries::id.desc())
        .select(entries::test_id)
        .first(db.conn())
        .optional()?,
    )
  }

  pub fn get_apps(&self) -> Result<Vec<AppSummary>> {
    let mut db = self.lock_read();
    diesel::sql_query(
      "SELECT r.app_name AS app_name, r.id AS latest_run_id,
              r.started_at AS latest_run_started_at, r.status AS latest_status,
              r.stove_version AS stove_version, r.metadata AS metadata
         FROM runs r WHERE r.id = (
           SELECT r3.id FROM runs r3 WHERE r3.app_name = r.app_name
           ORDER BY r3.started_at DESC, r3.id DESC LIMIT 1
         ) ORDER BY app_name",
    )
    .load::<AppSummaryRow>(db.conn())?
    .into_iter()
    .map(|row| row.into_domain())
    .collect()
  }

  pub fn count_runs(&self, filter: &crate::storage::repository::RunFilter) -> Result<i64> {
    let mut db = self.lock_read();
    Ok(filtered_runs(filter)?.count().get_result(db.conn())?)
  }

  pub fn failed_run_counts(&self, apps: &[String]) -> Result<BTreeMap<String, i64>> {
    let mut db = self.lock_read();
    Ok(
      runs::table
        .filter(runs::app_name.eq_any(apps))
        .filter(runs::status.eq("FAILED"))
        .group_by(runs::app_name)
        .select((runs::app_name, diesel::dsl::count_star()))
        .load::<(String, i64)>(db.conn())?
        .into_iter()
        .collect(),
    )
  }

  pub fn get_failure_page(
    &self,
    filter: &crate::storage::repository::FailureFilter,
    limit: i64,
    position: Option<&crate::storage::repository::failure_page::Position>,
  ) -> Result<Vec<(Run, Test)>> {
    let mut db = self.lock_read();
    let rows = crate::storage::repository::failure_page::failure_query!(filter, position)
      .order((
        runs::started_at.desc(),
        runs::id.desc(),
        tests::started_at.asc(),
        tests::id.asc(),
      ))
      .limit(limit)
      .select((runs::all_columns, tests::all_columns))
      .load::<(RunRow<String>, TestRow)>(db.conn())?;
    drop(db);
    rows
      .into_iter()
      .map(|(run, test)| Ok((Run::try_from(run)?, Test::try_from(test)?)))
      .collect()
  }

  pub fn count_failures(&self, filter: &crate::storage::repository::FailureFilter) -> Result<i64> {
    let mut db = self.lock_read();
    let position: Option<&crate::storage::repository::failure_page::Position> = None;
    Ok(
      crate::storage::repository::failure_page::failure_query!(filter, position)
        .count()
        .get_result(db.conn())?,
    )
  }

  pub fn get_run_page(
    &self,
    filter: &crate::storage::repository::RunFilter,
    limit: i64,
    position: Option<&crate::storage::repository::run_page::Position>,
  ) -> Result<Vec<Run>> {
    let mut db = self.lock_read();
    let mut query = filtered_runs(filter)?;
    if let Some(position) = position {
      query = query.filter(
        diesel::dsl::sql::<diesel::sql_types::Bool>("(started_at, id) < (")
          .bind::<Text, _>(&position.started_at)
          .sql(", ")
          .bind::<Text, _>(&position.id)
          .sql(")"),
      );
    }
    let rows = query
      .order((runs::started_at.desc(), runs::id.desc()))
      .limit(limit)
      .select(runs::all_columns)
      .load::<RunRow<String>>(db.conn())?;
    drop(db);
    rows.into_iter().map(Run::try_from).collect()
  }

  pub fn get_run(&self, run_id: &str) -> Result<Option<Run>> {
    let mut db = self.lock_read();
    runs::table
      .find(run_id)
      .select(runs::all_columns)
      .first::<RunRow<String>>(db.conn())
      .optional()?
      .map(Run::try_from)
      .transpose()
  }

  pub fn get_test_page(
    &self,
    filter: &crate::storage::repository::TestFilter,
    limit: i64,
    position: Option<&crate::storage::repository::test_page::Position>,
  ) -> Result<Vec<Test>> {
    let mut db = self.lock_read();
    let query = positioned_tests(filter, position);
    let rows = query
      .order((tests::started_at.asc(), tests::id.asc()))
      .limit(limit)
      .load::<TestRow>(db.conn())?;
    drop(db);
    rows.into_iter().map(Test::try_from).collect()
  }

  pub fn count_tests(
    &self,
    filter: &crate::storage::repository::TestFilter,
    position: Option<&crate::storage::repository::test_page::Position>,
  ) -> Result<i64> {
    let mut db = self.lock_read();
    Ok(
      positioned_tests(filter, position)
        .count()
        .get_result(db.conn())?,
    )
  }

  pub fn test_summary(&self, run_id: &str) -> Result<Vec<(String, i64)>> {
    let mut db = self.lock_read();
    Ok(
      tests::table
        .filter(tests::run_id.eq(run_id))
        .group_by(tests::status)
        .select((tests::status, diesel::dsl::count_star()))
        .load(db.conn())?,
    )
  }

  pub fn get_entries(&self, run_id: &str, test_id: &str, raw: bool) -> Result<Vec<Entry>> {
    let sql = if raw {
      RAW_ENTRIES_SQL.as_str()
    } else {
      COLLAPSED_ENTRIES_SQL
    };
    let mut db = self.lock_read();
    diesel::sql_query(sql)
      .bind::<Text, _>(run_id)
      .bind::<Text, _>(test_id)
      .load::<EntryRow>(db.conn())?
      .into_iter()
      .map(Entry::try_from)
      .collect::<Result<Vec<_>>>()
  }

  pub fn get_spans_for_test(&self, run_id: &str, test_id: &str) -> Result<Vec<Span>> {
    let mut db = self.lock_read();
    diesel::sql_query(&*SPANS_FOR_TEST_SQL)
      .bind::<Text, _>(run_id)
      .bind::<Text, _>(test_id)
      .load::<SpanRow>(db.conn())?
      .into_iter()
      .map(Span::try_from)
      .collect::<Result<Vec<_>>>()
  }

  pub fn get_trace(&self, trace_id: &str) -> Result<Vec<Span>> {
    let mut db = self.lock_read();
    spans::table
      .filter(spans::trace_id.eq(trace_id))
      .order(spans::start_time_nanos)
      .select(spans::all_columns)
      .load::<SpanRow>(db.conn())?
      .into_iter()
      .map(Span::try_from)
      .collect::<Result<Vec<_>>>()
  }

  pub fn get_snapshots(&self, run_id: &str, test_id: &str) -> Result<Vec<Snapshot>> {
    let mut db = self.lock_read();
    Ok(
      snapshots::table
        .filter(snapshots::run_id.eq(run_id))
        .filter(snapshots::test_id.eq(test_id))
        .order(snapshots::id)
        .select(snapshots::all_columns)
        .load::<SnapshotRow>(db.conn())?
        .into_iter()
        .map(Snapshot::from)
        .collect(),
    )
  }

  pub fn get_mock_interactions(
    &self,
    run_id: &str,
    scope: EvidenceScope<'_>,
  ) -> Result<Vec<MockInteraction>> {
    let mut db = self.lock_read();
    let mut query = mock_interactions::table
      .filter(mock_interactions::run_id.eq(run_id))
      .into_boxed::<diesel::sqlite::Sqlite>();
    match scope {
      EvidenceScope::Run => {}
      EvidenceScope::Test(test_id) => {
        query = query.filter(mock_interactions::test_id.eq(test_id));
      }
      EvidenceScope::Unattributed => {
        query = query.filter(mock_interactions::test_id.is_null());
      }
    }
    query
      .order(mock_interactions::id)
      .select(mock_interactions::all_columns)
      .load::<MockInteractionRow>(db.conn())?
      .into_iter()
      .map(|row| row.into_domain())
      .collect()
  }

  pub fn get_mock_warnings(
    &self,
    run_id: &str,
    scope: EvidenceScope<'_>,
  ) -> Result<Vec<MockWarning>> {
    let mut db = self.lock_read();
    let mut query = mock_warnings::table
      .filter(mock_warnings::run_id.eq(run_id))
      .into_boxed::<diesel::sqlite::Sqlite>();
    match scope {
      EvidenceScope::Run => {}
      EvidenceScope::Test(test_id) => {
        query = query.filter(mock_warnings::test_id.eq(test_id));
      }
      EvidenceScope::Unattributed => {
        query = query.filter(mock_warnings::test_id.is_null());
      }
    }
    Ok(
      query
        .order(mock_warnings::id)
        .select(mock_warnings::all_columns)
        .load::<MockWarningRow>(db.conn())?
        .into_iter()
        .map(MockWarning::from)
        .collect(),
    )
  }
}

const RAW_ENTRIES_BASE: &str = "SELECT id, run_id, test_id, timestamp, system, action, result,
  input, output, metadata, expected, actual, error, trace_id,
  assertion_id,
  1 AS attempt_count,
  CASE WHEN result IN ('FAILED', 'ERROR') THEN 1 ELSE 0 END AS failure_count
  FROM entries WHERE run_id = $1 AND test_id = $2";

const COLLAPSED_ENTRIES_SQL: &str = "WITH correlated AS (
  SELECT id, run_id, test_id, timestamp, system, action, result, input, output, metadata,
         expected, actual, error, trace_id,
         assertion_id
    FROM entries WHERE run_id = ? AND test_id = ?
), ranked AS (
  SELECT *, COUNT(*) OVER (PARTITION BY assertion_id) AS attempt_count,
         SUM(CASE WHEN result IN ('FAILED', 'ERROR') THEN 1 ELSE 0 END)
           OVER (PARTITION BY assertion_id) AS failure_count,
         ROW_NUMBER() OVER (PARTITION BY assertion_id ORDER BY id DESC) AS attempt_rank
    FROM correlated
)
SELECT id, run_id, test_id, timestamp, system, action, result, input, output, metadata,
       expected, actual, error, trace_id, assertion_id, attempt_count, failure_count
  FROM ranked WHERE attempt_rank = 1 ORDER BY timestamp, id";

const SPANS_FOR_TEST_BASE: &str = "SELECT id, run_id, trace_id, span_id, parent_span_id,
  operation_name, service_name, start_time_nanos, end_time_nanos, status, attributes,
  exception_type, exception_message, exception_stack_trace FROM spans
  WHERE run_id = $1 AND trace_id IN (
    SELECT trace_id FROM entries WHERE run_id = $1 AND test_id = $2 AND trace_id != ''
    UNION SELECT DISTINCT trace_id FROM spans WHERE run_id = $1 AND (
      json_extract(attributes, '$.\"x-stove-test-id\"') = $2 OR
      json_extract(attributes, '$.\"X-Stove-Test-Id\"') = $2 OR
      json_extract(attributes, '$.\"stove.test.id\"') = $2 OR
      json_extract(attributes, '$.\"stove_test_id\"') = $2
    )
  )";

// Database-specific casts and correlation predicates remain in the base queries.
// These variants are assembled once and never rewrite executable SQL by substring.
static RAW_ENTRIES_SQL: std::sync::LazyLock<String> =
  std::sync::LazyLock::new(|| format!("{RAW_ENTRIES_BASE} ORDER BY timestamp, id"));
static ENTRY_BY_ID_SQL: std::sync::LazyLock<String> =
  std::sync::LazyLock::new(|| format!("{RAW_ENTRIES_BASE} AND id = $3"));
static ENTRIES_BEFORE_SQL: std::sync::LazyLock<String> = std::sync::LazyLock::new(|| {
  format!(
    "{RAW_ENTRIES_BASE} AND (timestamp < $3 OR (timestamp = $3 AND id < $4)) ORDER BY timestamp DESC, id DESC LIMIT $5"
  )
});
static ENTRIES_AFTER_SQL: std::sync::LazyLock<String> = std::sync::LazyLock::new(|| {
  format!(
    "{RAW_ENTRIES_BASE} AND (timestamp > $3 OR (timestamp = $3 AND id > $4)) ORDER BY timestamp, id LIMIT $5"
  )
});
static SPANS_FOR_TEST_SQL: std::sync::LazyLock<String> =
  std::sync::LazyLock::new(|| format!("{SPANS_FOR_TEST_BASE} ORDER BY start_time_nanos"));
static TEST_SPAN_BY_ID_SQL: std::sync::LazyLock<String> =
  std::sync::LazyLock::new(|| format!("{SPANS_FOR_TEST_BASE} AND id = $3"));

fn filtered_runs(
  filter: &crate::storage::repository::RunFilter,
) -> Result<runs::BoxedQuery<'static, diesel::sqlite::Sqlite>> {
  let mut query = runs::table.into_boxed::<diesel::sqlite::Sqlite>();
  if let Some(app) = &filter.app {
    query = query.filter(runs::app_name.eq(app.clone()));
  }
  if let Some(status) = &filter.status {
    query = query.filter(runs::status.eq(status.to_string()));
  }
  let metadata = serde_json::to_string(&filter.metadata)?;
  if !filter.metadata.is_empty() {
    query = query.filter(diesel::dsl::sql::<diesel::sql_types::Bool>("NOT EXISTS (SELECT 1 FROM json_each(")
        .bind::<Text, _>(metadata).sql(") wanted WHERE NOT EXISTS (SELECT 1 FROM json_each(runs.metadata) actual WHERE actual.key = wanted.key AND actual.type = 'text' AND EXISTS (SELECT 1 FROM json_each(wanted.value) selected WHERE selected.value = actual.value)))"));
  }
  Ok(query)
}

fn filtered_tests(
  filter: &crate::storage::repository::TestFilter,
) -> tests::BoxedQuery<'static, diesel::sqlite::Sqlite> {
  use crate::storage::repository::TestOutcome;
  let mut query = tests::table
    .filter(tests::run_id.eq(filter.run_id.clone()))
    .into_boxed::<diesel::sqlite::Sqlite>();
  match filter.outcome {
    TestOutcome::All => {}
    TestOutcome::Pass => query = query.filter(tests::status.eq("PASSED")),
    TestOutcome::Fail => query = query.filter(tests::status.eq_any(["FAILED", "ERROR"])),
  }
  if !filter.search.is_empty() {
    let pattern = format!(
      "%{}%",
      filter
        .search
        .to_lowercase()
        .replace('\\', "\\\\")
        .replace('%', "\\%")
        .replace('_', "\\_")
    );
    query = query.filter(diesel::dsl::sql::<diesel::sql_types::Bool>("(LOWER(tests.test_name) LIKE ")
      .bind::<Text, _>(pattern.clone()).sql(" ESCAPE '\\' OR LOWER(tests.spec_name) LIKE ")
      .bind::<Text, _>(pattern.clone()).sql(" ESCAPE '\\' OR EXISTS (SELECT 1 FROM json_each(tests.test_path) path WHERE LOWER(path.value) LIKE ")
      .bind::<Text, _>(pattern).sql(" ESCAPE '\\'))"));
  }
  query
}

fn positioned_tests(
  filter: &crate::storage::repository::TestFilter,
  position: Option<&crate::storage::repository::test_page::Position>,
) -> tests::BoxedQuery<'static, diesel::sqlite::Sqlite> {
  let mut query = filtered_tests(filter);
  if let Some(position) = position {
    query = query.filter(
      tests::started_at.gt(position.started_at.clone()).or(
        tests::started_at
          .eq(position.started_at.clone())
          .and(tests::id.gt(position.id.clone())),
      ),
    );
  }
  query
}
