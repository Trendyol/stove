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
  pub(in crate::storage::repository) fn apps_page(
    &self,
    request: &crate::storage::repository::pagination::AppPageRequest,
  ) -> Result<crate::storage::repository::pagination::Page<AppSummary>> {
    use crate::storage::repository::replay::{BOUNDS_SQL, ReplayBounds};
    let mut database = self.lock_read();
    database.build_transaction().repeatable_read().read_only().run(|conn| {
      let bounds = diesel::sql_query(BOUNDS_SQL).get_result::<ReplayBounds>(conn)?;
      let items = diesel::sql_query("SELECT r.app_name, r.id AS latest_run_id, r.started_at AS latest_run_started_at, r.status AS latest_status, r.stove_version, CAST(r.metadata AS TEXT) AS metadata FROM runs r WHERE ($1 IS NULL OR r.app_name > $1) AND LOWER(r.app_name) LIKE $2 ESCAPE '\\' AND r.id = (SELECT latest.id FROM runs latest WHERE latest.app_name = r.app_name ORDER BY latest.started_at DESC, latest.id DESC LIMIT 1) ORDER BY r.app_name LIMIT $3")
        .bind::<diesel::sql_types::Nullable<Text>, _>(request.after.as_deref()).bind::<Text, _>(&request.pattern).bind::<diesel::sql_types::BigInt, _>(i64::try_from(request.limit + 1).unwrap_or(1001)).load::<AppSummaryRow>(conn)?.into_iter().map(|row| row.into_domain()).collect::<std::result::Result<Vec<_>, _>>()?;
      request.finish(items, crate::storage::repository::distributed::live_event_id_to_u64(bounds.watermark)?)
    })
  }

  pub(in crate::storage::repository) fn runs_page(
    &self,
    request: &crate::storage::repository::pagination::RunPageRequest,
  ) -> Result<crate::storage::repository::pagination::Page<Run>> {
    use crate::storage::repository::replay::{BOUNDS_SQL, ReplayBounds};
    let mut database = self.lock_read();
    database
      .build_transaction()
      .repeatable_read()
      .read_only()
      .run(|conn| {
        let bounds = diesel::sql_query(BOUNDS_SQL).get_result::<ReplayBounds>(conn)?;
        let mut query = runs::table.into_boxed::<diesel::pg::Pg>();
        if let Some(app) = &request.app {
          query = query.filter(runs::app_name.eq(app));
        }
        if let Some(cursor) = &request.cursor {
          query = query.filter(
            runs::started_at.lt(&cursor.started_at).or(
              runs::started_at
                .eq(&cursor.started_at)
                .and(runs::id.lt(&cursor.id)),
            ),
          );
        }
        if !request.metadata.is_empty() {
          query = query.filter(runs::metadata.contains(serde_json::to_value(&request.metadata)?));
        }
        if !request.search.is_empty() {
          query = query.filter(
            diesel::dsl::sql::<diesel::sql_types::Bool>(
              "LOWER(id || ' ' || app_name || ' ' || CAST(metadata AS TEXT)) LIKE ",
            )
            .bind::<Text, _>(&request.pattern)
            .sql(" ESCAPE '\\'"),
          );
        }
        let items = query
          .order((runs::started_at.desc(), runs::id.desc()))
          .limit(i64::try_from(request.limit + 1).unwrap_or(1001))
          .select(runs::all_columns)
          .load::<RunRow<serde_json::Value>>(conn)?
          .into_iter()
          .map(Run::from)
          .collect();
        request.finish(
          items,
          crate::storage::repository::distributed::live_event_id_to_u64(bounds.watermark)?,
        )
      })
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

  pub(in crate::storage::repository) fn tests_page(
    &self,
    request: &crate::storage::repository::pagination::TestPageRequest,
  ) -> Result<crate::storage::repository::pagination::Page<Test>> {
    let mut database = self.lock_read();
    database
      .build_transaction()
      .repeatable_read()
      .read_only()
      .run(|conn| {
        use crate::storage::repository::replay::{BOUNDS_SQL, ReplayBounds};
        let bounds = diesel::sql_query(BOUNDS_SQL).get_result::<ReplayBounds>(conn)?;
        let mut query = tests::table
          .filter(tests::run_id.eq(&request.run))
          .into_boxed::<diesel::pg::Pg>();
        if let Some(cursor) = &request.cursor {
          query = query.filter(
            tests::started_at.gt(&cursor.started_at).or(
              tests::started_at
                .eq(&cursor.started_at)
                .and(tests::id.gt(&cursor.id)),
            ),
          );
        }
        if !request.search.is_empty() {
          query = query.filter(
            diesel::dsl::sql::<diesel::sql_types::Bool>(
              "LOWER(test_name || ' ' || spec_name) LIKE ",
            )
            .bind::<Text, _>(&request.pattern)
            .sql(" ESCAPE '\\'"),
          );
        }
        let items = query
          .order((tests::started_at, tests::id))
          .limit(i64::try_from(request.limit + 1).unwrap_or(1001))
          .select(tests::all_columns)
          .load::<TestRow>(conn)?
          .into_iter()
          .map(Test::from)
          .collect();
        request.finish(
          items,
          crate::storage::repository::distributed::live_event_id_to_u64(bounds.watermark)?,
        )
      })
  }

  pub fn get_tests_for_run(&self, run_id: &str) -> Result<Vec<Test>> {
    let mut conn = self.lock_read();
    Ok(
      tests::table
        .filter(tests::run_id.eq(run_id))
        .order((tests::started_at, tests::id))
        .select(tests::all_columns)
        .load::<TestRow>(&mut *conn)?
        .into_iter()
        .map(Test::from)
        .collect(),
    )
  }

  pub(in crate::storage::repository) fn entries_page(
    &self,
    request: &crate::storage::repository::pagination::EvidencePageRequest,
  ) -> Result<crate::storage::repository::pagination::Page<Entry>> {
    use crate::storage::repository::pagination::EntryPageRow;
    use crate::storage::repository::replay::{BOUNDS_SQL, ReplayBounds};
    let mut database = self.lock_read();
    database
      .build_transaction()
      .repeatable_read()
      .read_only()
      .run(|conn| {
        let bounds = diesel::sql_query(BOUNDS_SQL).get_result::<ReplayBounds>(conn)?;
        let sql =
          if request.kind == crate::storage::repository::pagination::EvidenceKind::RawEntries {
            RAW_ENTRIES_PAGE_SQL
          } else {
            COLLAPSED_ENTRIES_PAGE_SQL
          };
        let rows = diesel::sql_query(sql)
          .bind::<Text, _>(&request.run)
          .bind::<Text, _>(&request.test)
          .bind::<diesel::sql_types::BigInt, _>(request.after)
          .bind::<Text, _>(&request.pattern)
          .bind::<diesel::sql_types::BigInt, _>(i64::try_from(request.limit + 1).unwrap_or(1001))
          .load::<EntryPageRow>(conn)?;
        request.finish(
          rows
            .into_iter()
            .map(|row| (Entry::from(row.entry), row.cursor_id))
            .collect(),
          crate::storage::repository::distributed::live_event_id_to_u64(bounds.watermark)?,
        )
      })
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

  pub(in crate::storage::repository) fn spans_page(
    &self,
    request: &crate::storage::repository::pagination::EvidencePageRequest,
  ) -> Result<crate::storage::repository::pagination::Page<Span>> {
    use crate::storage::repository::pagination::EvidenceKind;
    use crate::storage::repository::replay::{BOUNDS_SQL, ReplayBounds};
    let mut database = self.lock_read();
    database.build_transaction().repeatable_read().read_only().run(|conn| {
      let bounds = diesel::sql_query(BOUNDS_SQL).get_result::<ReplayBounds>(conn)?;
      let sql = if request.kind == EvidenceKind::TraceSpans {
        "SELECT * FROM spans WHERE trace_id = $2 AND $1 = ''".to_string()
      } else { SPANS_FOR_TEST_SQL.trim_end_matches(" ORDER BY start_time_nanos").to_string() };
      let sql = format!("SELECT p.* FROM ({sql}) p WHERE id > $3 AND LOWER(operation_name || ' ' || service_name || ' ' || status || ' ' || COALESCE(attributes, '') || ' ' || COALESCE(exception_message, '')) LIKE $4 ESCAPE '\\' ORDER BY id LIMIT $5");
      let rows = diesel::sql_query(sql).bind::<Text, _>(&request.run).bind::<Text, _>(&request.test).bind::<diesel::sql_types::BigInt, _>(request.after).bind::<Text, _>(&request.pattern).bind::<diesel::sql_types::BigInt, _>(i64::try_from(request.limit + 1).unwrap_or(1001)).load::<SpanRow>(conn)?;
      let items = rows.into_iter().map(Span::from).map(|row| { let id = row.id; (row, id) }).collect();
      request.finish(items, crate::storage::repository::distributed::live_event_id_to_u64(bounds.watermark)?)
    })
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

  pub(in crate::storage::repository) fn snapshots_page(
    &self,
    request: &crate::storage::repository::pagination::EvidencePageRequest,
  ) -> Result<
    crate::storage::repository::pagination::Page<
      crate::storage::repository::pagination::SnapshotSummary,
    >,
  > {
    use crate::storage::repository::pagination::SnapshotSummary;
    use crate::storage::repository::replay::{BOUNDS_SQL, ReplayBounds};
    let mut database = self.lock_read();
    database
      .build_transaction()
      .repeatable_read()
      .read_only()
      .run(|conn| {
        let bounds = diesel::sql_query(BOUNDS_SQL).get_result::<ReplayBounds>(conn)?;
        let mut query = snapshots::table
          .filter(snapshots::run_id.eq(&request.run))
          .filter(snapshots::test_id.eq(&request.test))
          .filter(snapshots::id.gt(request.after))
          .into_boxed::<diesel::pg::Pg>();
        if !request.search.is_empty() {
          query = query.filter(
            diesel::dsl::sql::<diesel::sql_types::Bool>(
              "LOWER(system || ' ' || summary || ' ' || state_json) LIKE ",
            )
            .bind::<Text, _>(&request.pattern)
            .sql(" ESCAPE '\\'"),
          );
        }
        let rows = query
          .order(snapshots::id)
          .limit(i64::try_from(request.limit + 1).unwrap_or(1001))
          .select((
            snapshots::id,
            snapshots::run_id,
            snapshots::test_id,
            snapshots::system,
            snapshots::summary,
            snapshots::captured_at,
            snapshots::trigger_kind,
            diesel::dsl::sql::<diesel::sql_types::BigInt>("octet_length(state_json)::bigint"),
          ))
          .load::<SnapshotSummary>(conn)?;
        request.finish(
          rows
            .into_iter()
            .map(|row| {
              let id = row.id;
              (row, id)
            })
            .collect(),
          crate::storage::repository::distributed::live_event_id_to_u64(bounds.watermark)?,
        )
      })
  }

  pub(in crate::storage::repository) fn snapshot_detail(
    &self,
    run: &str,
    test: &str,
    id: i64,
  ) -> Result<Option<Snapshot>> {
    let mut database = self.lock_read();
    Ok(
      snapshots::table
        .filter(snapshots::run_id.eq(run))
        .filter(snapshots::test_id.eq(test))
        .filter(snapshots::id.eq(id))
        .select(snapshots::all_columns)
        .first::<SnapshotRow>(&mut *database)
        .optional()?
        .map(Snapshot::from),
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

  pub(in crate::storage::repository) fn mock_interactions_page(
    &self,
    request: &crate::storage::repository::pagination::EvidencePageRequest,
  ) -> Result<crate::storage::repository::pagination::Page<MockInteraction>> {
    use crate::storage::repository::pagination::EvidenceKind;
    use crate::storage::repository::replay::{BOUNDS_SQL, ReplayBounds};
    let mut database = self.lock_read();
    database.build_transaction().repeatable_read().read_only().run(|conn| {
      let bounds = diesel::sql_query(BOUNDS_SQL).get_result::<ReplayBounds>(conn)?;
      let mut query = mock_interactions::table.filter(mock_interactions::run_id.eq(&request.run)).filter(mock_interactions::id.gt(request.after)).into_boxed::<diesel::pg::Pg>();
      if request.kind == EvidenceKind::AmbientMockInteractions { query = query.filter(mock_interactions::test_id.is_null()); }
      else if !request.test.is_empty() { query = query.filter(mock_interactions::test_id.eq(&request.test)); }
      if !request.search.is_empty() {
        query = query.filter(diesel::dsl::sql::<diesel::sql_types::Bool>("LOWER(system || ' ' || method || ' ' || target || ' ' || COALESCE(request_body, '') || ' ' || COALESCE(response_body, '')) LIKE ").bind::<Text, _>(&request.pattern).sql(" ESCAPE '\\'"));
      }
      let rows = query.order(mock_interactions::id).limit(i64::try_from(request.limit + 1).unwrap_or(1001)).select(mock_interactions::all_columns).load::<MockInteractionRow>(conn)?;
      let items = rows.into_iter().map(|row| row.into_domain().map(|row| { let id = row.id; (row, id) })).collect::<std::result::Result<Vec<_>, _>>()?;
      request.finish(items, crate::storage::repository::distributed::live_event_id_to_u64(bounds.watermark)?)
    })
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

  pub(in crate::storage::repository) fn mock_warnings_page(
    &self,
    request: &crate::storage::repository::pagination::EvidencePageRequest,
  ) -> Result<crate::storage::repository::pagination::Page<MockWarning>> {
    use crate::storage::repository::pagination::EvidenceKind;
    use crate::storage::repository::replay::{BOUNDS_SQL, ReplayBounds};
    let mut database = self.lock_read();
    database
      .build_transaction()
      .repeatable_read()
      .read_only()
      .run(|conn| {
        let bounds = diesel::sql_query(BOUNDS_SQL).get_result::<ReplayBounds>(conn)?;
        let mut query = mock_warnings::table
          .filter(mock_warnings::run_id.eq(&request.run))
          .filter(mock_warnings::id.gt(request.after))
          .into_boxed::<diesel::pg::Pg>();
        if request.kind == EvidenceKind::AmbientMockWarnings {
          query = query.filter(mock_warnings::test_id.is_null());
        } else if !request.test.is_empty() {
          query = query.filter(mock_warnings::test_id.eq(&request.test));
        }
        if !request.search.is_empty() {
          query = query.filter(
            diesel::dsl::sql::<diesel::sql_types::Bool>(
              "LOWER(system || ' ' || kind || ' ' || message || ' ' || COALESCE(target, '')) LIKE ",
            )
            .bind::<Text, _>(&request.pattern)
            .sql(" ESCAPE '\\'"),
          );
        }
        let rows = query
          .order(mock_warnings::id)
          .limit(i64::try_from(request.limit + 1).unwrap_or(1001))
          .select(mock_warnings::all_columns)
          .load::<MockWarningRow>(conn)?;
        let items = rows
          .into_iter()
          .map(MockWarning::from)
          .map(|row| {
            let id = row.id;
            (row, id)
          })
          .collect();
        request.finish(
          items,
          crate::storage::repository::distributed::live_event_id_to_u64(bounds.watermark)?,
        )
      })
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

impl crate::ingest::PreparationLookup for PgConnection {
  fn get_open_assertion(
    &mut self,
    run_id: &str,
    test_id: &str,
    correlation_key: &str,
  ) -> Result<Option<OpenAssertion>> {
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
    .get_result::<OpenAssertionRow>(self)
    .optional()?;
    Ok(row.map(OpenAssertion::from))
  }

  fn get_test_id_for_trace(&mut self, run_id: &str, trace_id: &str) -> Result<Option<String>> {
    Ok(
      entries::table
        .filter(entries::run_id.eq(run_id))
        .filter(entries::trace_id.eq(trace_id))
        .order(entries::id.desc())
        .select(entries::test_id)
        .first(self)
        .optional()?,
    )
  }
}

const RAW_ENTRIES_PAGE_SQL: &str = "SELECT e.id, e.run_id, e.test_id, e.timestamp, e.system, e.action, e.result, e.input, e.output, e.metadata, e.expected, e.actual, e.error, e.trace_id, CASE WHEN assertion_id = '' THEN 'legacy:' || id ELSE assertion_id END AS assertion_id, 1::bigint AS attempt_count, CASE WHEN result IN ('FAILED','ERROR') THEN 1 ELSE 0 END::bigint AS failure_count, e.id AS cursor_id FROM entries e WHERE run_id = $1 AND test_id = $2 AND id > $3 AND LOWER(e.system || ' ' || e.action || ' ' || e.result || ' ' || COALESCE(e.input, '') || ' ' || COALESCE(e.output, '') || ' ' || COALESCE(e.error, '') || ' ' || COALESCE(e.expected, '') || ' ' || COALESCE(e.actual, '')) LIKE $4 ESCAPE '\\' ORDER BY e.id LIMIT $5";

const COLLAPSED_ENTRIES_PAGE_SQL: &str = "WITH grouped AS (SELECT CASE WHEN assertion_id = '' THEN 'legacy:' || id ELSE assertion_id END AS assertion_id, MIN(id) AS cursor_id, MAX(id) AS latest_id, COUNT(*) AS attempt_count, SUM(CASE WHEN result IN ('FAILED','ERROR') THEN 1 ELSE 0 END)::bigint AS failure_count FROM entries WHERE run_id = $1 AND test_id = $2 GROUP BY CASE WHEN assertion_id = '' THEN 'legacy:' || id ELSE assertion_id END HAVING MIN(id) > $3) SELECT e.id, e.run_id, e.test_id, e.timestamp, e.system, e.action, e.result, e.input, e.output, e.metadata, e.expected, e.actual, e.error, e.trace_id, g.assertion_id, g.attempt_count, g.failure_count, g.cursor_id FROM grouped g JOIN entries e ON e.id = g.latest_id WHERE LOWER(e.system || ' ' || e.action || ' ' || e.result || ' ' || COALESCE(e.input, '') || ' ' || COALESCE(e.output, '') || ' ' || COALESCE(e.error, '') || ' ' || COALESCE(e.expected, '') || ' ' || COALESCE(e.actual, '')) LIKE $4 ESCAPE '\\' ORDER BY g.cursor_id LIMIT $5";
