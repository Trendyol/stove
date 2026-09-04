use std::collections::BTreeMap;

use diesel::PgJsonbExpressionMethods;
use diesel::prelude::*;
use diesel::sql_types::Text;

use super::PostgresBackend;
use crate::error::Result;
use crate::storage::models::{
  AppSummary, Entry, MockInteraction, MockWarning, OpenAssertion, Run, Snapshot, Span, Test,
};
use crate::storage::repository::mapping::{
  AppSummaryRow, EntryRow, MockInteractionRow, MockWarningRow, OpenAssertionRow, RunRow,
  SnapshotRow, SpanRow, TestRow,
};
use crate::storage::repository::reads::EvidenceScope;
use crate::storage::schema::postgres::{
  entries, mock_interactions, mock_warnings, runs, snapshots, spans, tests,
};

impl PostgresBackend {
  pub fn get_open_assertion(
    &self,
    run_id: &str,
    test_id: &str,
    correlation_key: &str,
  ) -> Result<Option<OpenAssertion>> {
    let mut conn = self.lock_read();
    let row = diesel::sql_query(
      "WITH latest AS (
         SELECT assertion_id, result FROM entries
          WHERE run_id = $1 AND test_id = $2 AND correlation_key = $3
          ORDER BY id DESC LIMIT 1
       )
       SELECT latest.assertion_id AS assertion_id, COUNT(entries.id) AS attempt_count,
              SUM(CASE WHEN entries.result IN ('FAILED', 'ERROR') THEN 1 ELSE 0 END)::bigint AS failure_count
         FROM latest JOIN entries ON entries.run_id = $1 AND entries.test_id = $2
          AND entries.assertion_id = latest.assertion_id
        WHERE latest.result IN ('FAILED', 'ERROR') GROUP BY latest.assertion_id",
    )
    .bind::<Text, _>(run_id)
    .bind::<Text, _>(test_id)
    .bind::<Text, _>(correlation_key)
    .get_result::<OpenAssertionRow>(&mut *conn)
    .optional()?;
    Ok(row.map(OpenAssertion::from))
  }

  pub fn get_test_id_for_trace(&self, run_id: &str, trace_id: &str) -> Result<Option<String>> {
    let mut conn = self.lock_read();
    Ok(
      entries::table
        .filter(entries::run_id.eq(run_id))
        .filter(entries::trace_id.eq(trace_id))
        .order(entries::id.desc())
        .select(entries::test_id)
        .first(&mut *conn)
        .optional()?,
    )
  }

  pub fn get_apps(&self) -> Result<Vec<AppSummary>> {
    let mut conn = self.lock_read();
    diesel::sql_query(
      "SELECT DISTINCT ON (app_name) app_name, id AS latest_run_id,
              started_at AS latest_run_started_at, status AS latest_status,
              stove_version, metadata::text AS metadata
         FROM runs ORDER BY app_name, started_at DESC, id DESC",
    )
    .load::<AppSummaryRow>(&mut *conn)?
    .into_iter()
    .map(|row| Ok(row.into_domain()?))
    .collect()
  }

  pub fn get_runs_filtered(
    &self,
    app_name: Option<&str>,
    metadata: &BTreeMap<String, String>,
  ) -> Result<Vec<Run>> {
    let mut conn = self.lock_read();
    let mut query = runs::table.into_boxed::<diesel::pg::Pg>();
    if let Some(app_name) = app_name {
      query = query.filter(runs::app_name.eq(app_name));
    }
    if !metadata.is_empty() {
      query = query.filter(runs::metadata.contains(serde_json::to_value(metadata)?));
    }
    Ok(
      query
        .order((runs::started_at.desc(), runs::id.desc()))
        .select(runs::all_columns)
        .load::<RunRow<serde_json::Value>>(&mut *conn)?
        .into_iter()
        .map(Run::from)
        .collect(),
    )
  }

  pub fn get_run(&self, run_id: &str) -> Result<Option<Run>> {
    let mut conn = self.lock_read();
    Ok(
      runs::table
        .find(run_id)
        .select(runs::all_columns)
        .first::<RunRow<serde_json::Value>>(&mut *conn)
        .optional()?
        .map(Run::from),
    )
  }

  pub fn get_tests_for_run(&self, run_id: &str) -> Result<Vec<Test>> {
    let mut conn = self.lock_read();
    Ok(
      tests::table
        .filter(tests::run_id.eq(run_id))
        .order(tests::started_at)
        .select(tests::all_columns)
        .load::<TestRow>(&mut *conn)?
        .into_iter()
        .map(Test::from)
        .collect(),
    )
  }

  pub fn get_entries(&self, run_id: &str, test_id: &str, raw: bool) -> Result<Vec<Entry>> {
    let sql = if raw {
      RAW_ENTRIES_SQL
    } else {
      COLLAPSED_ENTRIES_SQL
    };
    let mut conn = self.lock_read();
    Ok(
      diesel::sql_query(sql)
        .bind::<Text, _>(run_id)
        .bind::<Text, _>(test_id)
        .load::<EntryRow>(&mut *conn)?
        .into_iter()
        .map(Entry::from)
        .collect(),
    )
  }

  pub fn get_spans_for_test(&self, run_id: &str, test_id: &str) -> Result<Vec<Span>> {
    let mut conn = self.lock_read();
    Ok(
      diesel::sql_query(SPANS_FOR_TEST_SQL)
        .bind::<Text, _>(run_id)
        .bind::<Text, _>(test_id)
        .load::<SpanRow>(&mut *conn)?
        .into_iter()
        .map(Span::from)
        .collect(),
    )
  }

  pub fn get_trace(&self, trace_id: &str) -> Result<Vec<Span>> {
    let mut conn = self.lock_read();
    Ok(
      spans::table
        .filter(spans::trace_id.eq(trace_id))
        .order(spans::start_time_nanos)
        .select(spans::all_columns)
        .load::<SpanRow>(&mut *conn)?
        .into_iter()
        .map(Span::from)
        .collect(),
    )
  }

  pub fn get_snapshots(&self, run_id: &str, test_id: &str) -> Result<Vec<Snapshot>> {
    let mut conn = self.lock_read();
    Ok(
      snapshots::table
        .filter(snapshots::run_id.eq(run_id))
        .filter(snapshots::test_id.eq(test_id))
        .order(snapshots::id)
        .select(snapshots::all_columns)
        .load::<SnapshotRow>(&mut *conn)?
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
    let mut conn = self.lock_read();
    let mut query = mock_interactions::table
      .filter(mock_interactions::run_id.eq(run_id))
      .into_boxed::<diesel::pg::Pg>();
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
      .load::<MockInteractionRow>(&mut *conn)?
      .into_iter()
      .map(|row| Ok(row.into_domain()?))
      .collect()
  }

  pub fn get_mock_warnings(
    &self,
    run_id: &str,
    scope: EvidenceScope<'_>,
  ) -> Result<Vec<MockWarning>> {
    let mut conn = self.lock_read();
    let mut query = mock_warnings::table
      .filter(mock_warnings::run_id.eq(run_id))
      .into_boxed::<diesel::pg::Pg>();
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
        .load::<MockWarningRow>(&mut *conn)?
        .into_iter()
        .map(MockWarning::from)
        .collect(),
    )
  }
}

const RAW_ENTRIES_SQL: &str = "SELECT id, run_id, test_id, timestamp, system, action, result,
  input, output, metadata, expected, actual, error, trace_id,
  CASE WHEN assertion_id = '' THEN 'legacy:' || id ELSE assertion_id END AS assertion_id,
  1::bigint AS attempt_count,
  CASE WHEN result IN ('FAILED', 'ERROR') THEN 1::bigint ELSE 0::bigint END AS failure_count
  FROM entries WHERE run_id = $1 AND test_id = $2 ORDER BY timestamp, id";

const COLLAPSED_ENTRIES_SQL: &str = "WITH correlated AS (
  SELECT id, run_id, test_id, timestamp, system, action, result, input, output, metadata,
         expected, actual, error, trace_id,
         CASE WHEN assertion_id = '' THEN 'legacy:' || id ELSE assertion_id END AS assertion_id
    FROM entries WHERE run_id = $1 AND test_id = $2
), ranked AS (
  SELECT *, COUNT(*) OVER (PARTITION BY assertion_id) AS attempt_count,
         SUM(CASE WHEN result IN ('FAILED', 'ERROR') THEN 1 ELSE 0 END)
           OVER (PARTITION BY assertion_id)::bigint AS failure_count,
         ROW_NUMBER() OVER (PARTITION BY assertion_id ORDER BY id DESC) AS attempt_rank
    FROM correlated
)
SELECT id, run_id, test_id, timestamp, system, action, result, input, output, metadata,
       expected, actual, error, trace_id, assertion_id, attempt_count, failure_count
  FROM ranked WHERE attempt_rank = 1 ORDER BY timestamp, id";

const SPANS_FOR_TEST_SQL: &str = "SELECT id, run_id, trace_id, span_id, parent_span_id,
  operation_name, service_name, start_time_nanos, end_time_nanos, status, attributes,
  exception_type, exception_message, exception_stack_trace FROM spans
  WHERE run_id = $1 AND trace_id IN (
    SELECT trace_id FROM entries WHERE run_id = $1 AND test_id = $2 AND trace_id <> ''
    UNION SELECT DISTINCT trace_id FROM spans WHERE run_id = $1 AND (
      attributes::jsonb ->> 'x-stove-test-id' = $2 OR
      attributes::jsonb ->> 'X-Stove-Test-Id' = $2 OR
      attributes::jsonb ->> 'stove.test.id' = $2 OR
      attributes::jsonb ->> 'stove_test_id' = $2
    )
  ) ORDER BY start_time_nanos";
