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
      "SELECT r.app_name AS app_name, r.id AS latest_run_id, r.status AS latest_status,
              r.stove_version AS stove_version, r.metadata AS metadata
         FROM runs r WHERE r.id = (
           SELECT r3.id FROM runs r3 WHERE r3.app_name = r.app_name
           ORDER BY r3.started_at DESC, r3.rowid DESC LIMIT 1
         ) ORDER BY app_name",
    )
    .load::<AppSummaryRow>(db.conn())?
    .into_iter()
    .map(|row| Ok(row.into_domain()?))
    .collect()
  }

  pub fn get_runs_filtered(
    &self,
    app_name: Option<&str>,
    metadata: &BTreeMap<String, String>,
  ) -> Result<Vec<Run>> {
    let mut db = self.lock_read();
    let mut query = runs::table.into_boxed::<diesel::sqlite::Sqlite>();
    if let Some(app_name) = app_name {
      query = query.filter(runs::app_name.eq(app_name));
    }
    let mut found = query
      .order((runs::started_at.desc(), runs::id.desc()))
      .select(runs::all_columns)
      .load::<RunRow<String>>(db.conn())?
      .into_iter()
      .map(Run::from)
      .collect::<Vec<_>>();
    found.retain(|run| {
      metadata
        .iter()
        .all(|(key, value)| run.metadata.get(key) == Some(value))
    });
    Ok(found)
  }

  pub fn get_run(&self, run_id: &str) -> Result<Option<Run>> {
    let mut db = self.lock_read();
    Ok(
      runs::table
        .find(run_id)
        .select(runs::all_columns)
        .first::<RunRow<String>>(db.conn())
        .optional()?
        .map(Run::from),
    )
  }

  pub fn get_tests_for_run(&self, run_id: &str) -> Result<Vec<Test>> {
    let mut db = self.lock_read();
    Ok(
      tests::table
        .filter(tests::run_id.eq(run_id))
        .order(tests::started_at)
        .select(tests::all_columns)
        .load::<TestRow>(db.conn())?
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
    let mut db = self.lock_read();
    Ok(
      diesel::sql_query(sql)
        .bind::<Text, _>(run_id)
        .bind::<Text, _>(test_id)
        .load::<EntryRow>(db.conn())?
        .into_iter()
        .map(Entry::from)
        .collect(),
    )
  }

  pub fn get_spans_for_test(&self, run_id: &str, test_id: &str) -> Result<Vec<Span>> {
    let mut db = self.lock_read();
    Ok(
      diesel::sql_query(SPANS_FOR_TEST_SQL)
        .bind::<Text, _>(run_id)
        .bind::<Text, _>(test_id)
        .load::<SpanRow>(db.conn())?
        .into_iter()
        .map(Span::from)
        .collect(),
    )
  }

  pub fn get_trace(&self, trace_id: &str) -> Result<Vec<Span>> {
    let mut db = self.lock_read();
    Ok(
      spans::table
        .filter(spans::trace_id.eq(trace_id))
        .order(spans::start_time_nanos)
        .select(spans::all_columns)
        .load::<SpanRow>(db.conn())?
        .into_iter()
        .map(Span::from)
        .collect(),
    )
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
      .map(|row| Ok(row.into_domain()?))
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

const RAW_ENTRIES_SQL: &str = "SELECT id, run_id, test_id, timestamp, system, action, result,
  input, output, metadata, expected, actual, error, trace_id,
  CASE WHEN assertion_id = '' THEN 'legacy:' || id ELSE assertion_id END AS assertion_id,
  1 AS attempt_count,
  CASE WHEN result IN ('FAILED', 'ERROR') THEN 1 ELSE 0 END AS failure_count
  FROM entries WHERE run_id = ? AND test_id = ? ORDER BY timestamp, id";

const COLLAPSED_ENTRIES_SQL: &str = "WITH correlated AS (
  SELECT id, run_id, test_id, timestamp, system, action, result, input, output, metadata,
         expected, actual, error, trace_id,
         CASE WHEN assertion_id = '' THEN 'legacy:' || id ELSE assertion_id END AS assertion_id
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

const SPANS_FOR_TEST_SQL: &str = "SELECT id, run_id, trace_id, span_id, parent_span_id,
  operation_name, service_name, start_time_nanos, end_time_nanos, status, attributes,
  exception_type, exception_message, exception_stack_trace FROM spans
  WHERE run_id = ? AND trace_id IN (
    SELECT trace_id FROM entries WHERE run_id = ?1 AND test_id = ?2 AND trace_id != ''
    UNION SELECT DISTINCT trace_id FROM spans WHERE run_id = ?1 AND (
      json_extract(attributes, '$.\"x-stove-test-id\"') = ?2 OR
      json_extract(attributes, '$.\"X-Stove-Test-Id\"') = ?2 OR
      json_extract(attributes, '$.\"stove.test.id\"') = ?2 OR
      json_extract(attributes, '$.\"stove_test_id\"') = ?2
    )
  ) ORDER BY start_time_nanos";
