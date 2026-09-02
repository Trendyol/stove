use std::collections::BTreeMap;

use super::SqliteBackend;
use super::mapping::{
  MOCK_INTERACTION_COLUMNS, MOCK_WARNING_COLUMNS, RUN_COLUMNS, SNAPSHOT_COLUMNS, SPAN_COLUMNS,
  entry_from_row, mock_interaction_from_row, mock_warning_from_row, parse_run_status, run_from_row,
  snapshot_from_row, span_from_row, test_from_row,
};
use crate::error::Result;
use crate::storage::models::{
  AppSummary, Entry, MockInteraction, MockWarning, Run, Snapshot, Span, Test,
};

const NORMALIZED_ASSERTION_ID_SQL: &str =
  "CASE WHEN assertion_id = '' THEN 'legacy:' || id ELSE assertion_id END";

impl SqliteBackend {
  pub fn get_apps(&self) -> Result<Vec<AppSummary>> {
    let db = self.lock_read();
    let mut stmt = db.conn().prepare(
      "SELECT r.app_name, r.id, r.status, r.stove_version, r.metadata
             FROM runs r
             WHERE r.id = (
               SELECT r3.id
               FROM runs r3
               WHERE r3.app_name = r.app_name
               ORDER BY r3.started_at DESC, r3.rowid DESC
               LIMIT 1
             )
             ORDER BY app_name",
    )?;
    let rows = stmt.query_map([], |row| {
      Ok(AppSummary {
        app_name: row.get(0)?,
        latest_run_id: row.get(1)?,
        latest_status: parse_run_status(&row.get::<_, String>(2)?),
        stove_version: row.get(3)?,
        metadata: serde_json::from_str(&row.get::<_, String>(4)?).unwrap_or_default(),
      })
    })?;
    Ok(rows.collect::<rusqlite::Result<Vec<_>>>()?)
  }

  pub fn get_runs_filtered(
    &self,
    app_name: Option<&str>,
    metadata: &BTreeMap<String, String>,
  ) -> Result<Vec<Run>> {
    let db = self.lock_read();
    let filter = match app_name {
      Some(_) => " WHERE app_name = ?1",
      None => "",
    };
    let sql =
      format!("SELECT {RUN_COLUMNS} FROM runs{filter} ORDER BY started_at DESC, rowid DESC");
    let mut stmt = db.conn().prepare(&sql)?;
    let rows = match app_name {
      Some(name) => stmt.query_map(rusqlite::params![name], run_from_row)?,
      None => stmt.query_map([], run_from_row)?,
    };
    let mut runs = rows.collect::<rusqlite::Result<Vec<_>>>()?;
    if !metadata.is_empty() {
      runs.retain(|run| {
        metadata
          .iter()
          .all(|(key, value)| run.metadata.get(key) == Some(value))
      });
    }
    Ok(runs)
  }

  pub fn get_run(&self, run_id: &str) -> Result<Option<Run>> {
    let db = self.lock_read();
    let sql = format!("SELECT {RUN_COLUMNS} FROM runs WHERE id = ?1");
    let mut stmt = db.conn().prepare(&sql)?;
    let mut rows = stmt.query_map(rusqlite::params![run_id], run_from_row)?;
    Ok(rows.next().transpose()?)
  }

  pub fn get_tests_for_run(&self, run_id: &str) -> Result<Vec<Test>> {
    let db = self.lock_read();
    let mut stmt = db.conn().prepare(
            "SELECT id, run_id, test_name, spec_name, test_path, started_at, ended_at, status, duration_ms, error FROM tests WHERE run_id = ?1 ORDER BY started_at",
        )?;
    let rows = stmt.query_map(rusqlite::params![run_id], test_from_row)?;
    Ok(rows.collect::<rusqlite::Result<Vec<_>>>()?)
  }

  pub fn get_entries(&self, run_id: &str, test_id: &str, raw: bool) -> Result<Vec<Entry>> {
    let db = self.lock_read();
    let sql = if raw {
      format!(
        "SELECT id, run_id, test_id, timestamp, system, action, result, input, output,
                metadata, expected, actual, error, trace_id,
                {NORMALIZED_ASSERTION_ID_SQL} AS assertion_id,
                1 AS attempt_count,
                CASE WHEN result IN ('FAILED', 'ERROR') THEN 1 ELSE 0 END AS failure_count
           FROM entries
          WHERE run_id = ?1 AND test_id = ?2
          ORDER BY timestamp, id"
      )
    } else {
      format!(
        "WITH correlated AS (
         SELECT id, run_id, test_id, timestamp, system, action, result, input, output,
                metadata, expected, actual, error, trace_id,
                {NORMALIZED_ASSERTION_ID_SQL} AS assertion_id
           FROM entries
          WHERE run_id = ?1 AND test_id = ?2
       ),
       ranked AS (
         SELECT *,
                COUNT(*) OVER (PARTITION BY assertion_id) AS attempt_count,
                SUM(CASE WHEN result IN ('FAILED', 'ERROR') THEN 1 ELSE 0 END)
                  OVER (PARTITION BY assertion_id) AS failure_count,
                ROW_NUMBER() OVER (
                  PARTITION BY assertion_id
                  ORDER BY id DESC
                ) AS attempt_rank
           FROM correlated
       )
       SELECT id, run_id, test_id, timestamp, system, action, result, input, output,
              metadata, expected, actual, error, trace_id, assertion_id,
              attempt_count, failure_count
         FROM ranked
        WHERE attempt_rank = 1
        ORDER BY timestamp, id"
      )
    };
    let mut stmt = db.conn().prepare(&sql)?;
    let rows = stmt.query_map(rusqlite::params![run_id, test_id], entry_from_row)?;
    Ok(rows.collect::<rusqlite::Result<Vec<_>>>()?)
  }

  pub fn get_spans_for_test(&self, run_id: &str, test_id: &str) -> Result<Vec<Span>> {
    let db = self.lock_read();
    let sql = format!(
      "SELECT {SPAN_COLUMNS} FROM spans \
             WHERE run_id = ?1 AND trace_id IN ( \
               SELECT trace_id FROM entries WHERE run_id = ?1 AND test_id = ?2 AND trace_id != '' \
               UNION \
               SELECT DISTINCT trace_id FROM spans WHERE run_id = ?1 AND ( \
                 json_extract(attributes, '$.\"x-stove-test-id\"') = ?2 OR \
                 json_extract(attributes, '$.\"X-Stove-Test-Id\"') = ?2 OR \
                 json_extract(attributes, '$.\"stove.test.id\"') = ?2 OR \
                 json_extract(attributes, '$.\"stove_test_id\"') = ?2 \
               ) \
             ) \
             ORDER BY start_time_nanos"
    );
    let mut stmt = db.conn().prepare(&sql)?;
    let rows = stmt.query_map(rusqlite::params![run_id, test_id], span_from_row)?;
    Ok(rows.collect::<rusqlite::Result<Vec<_>>>()?)
  }

  pub fn get_trace(&self, trace_id: &str) -> Result<Vec<Span>> {
    let db = self.lock_read();
    let sql =
      format!("SELECT {SPAN_COLUMNS} FROM spans WHERE trace_id = ?1 ORDER BY start_time_nanos");
    let mut stmt = db.conn().prepare(&sql)?;
    let rows = stmt.query_map(rusqlite::params![trace_id], span_from_row)?;
    Ok(rows.collect::<rusqlite::Result<Vec<_>>>()?)
  }

  pub fn get_snapshots(&self, run_id: &str, test_id: &str) -> Result<Vec<Snapshot>> {
    let db = self.lock_read();
    let mut stmt = db.conn().prepare(&format!(
      "SELECT {SNAPSHOT_COLUMNS} FROM snapshots WHERE run_id = ?1 AND test_id = ?2 ORDER BY id"
    ))?;
    let rows = stmt.query_map(rusqlite::params![run_id, test_id], snapshot_from_row)?;
    Ok(rows.collect::<rusqlite::Result<Vec<_>>>()?)
  }

  pub fn get_mock_interactions(
    &self,
    run_id: &str,
    test_id: Option<&str>,
    unattributed_only: bool,
  ) -> Result<Vec<MockInteraction>> {
    let db = self.lock_read();
    let filter = match test_id {
      Some(_) => " AND test_id = ?2",
      None if unattributed_only => " AND test_id IS NULL",
      None => "",
    };
    let mut stmt = db.conn().prepare(&format!(
      "SELECT {MOCK_INTERACTION_COLUMNS} FROM mock_interactions WHERE run_id = ?1{filter} ORDER BY id"
    ))?;
    let rows = match test_id {
      Some(test_id) => stmt.query_map(
        rusqlite::params![run_id, test_id],
        mock_interaction_from_row,
      )?,
      None => stmt.query_map(rusqlite::params![run_id], mock_interaction_from_row)?,
    };
    Ok(rows.collect::<rusqlite::Result<Vec<_>>>()?)
  }

  pub fn get_mock_warnings(
    &self,
    run_id: &str,
    test_id: Option<&str>,
    unattributed_only: bool,
  ) -> Result<Vec<MockWarning>> {
    let db = self.lock_read();
    let filter = match test_id {
      Some(_) => " AND test_id = ?2",
      None if unattributed_only => " AND test_id IS NULL",
      None => "",
    };
    let mut stmt = db.conn().prepare(&format!(
      "SELECT {MOCK_WARNING_COLUMNS} FROM mock_warnings WHERE run_id = ?1{filter} ORDER BY id"
    ))?;
    let rows = match test_id {
      Some(test_id) => stmt.query_map(rusqlite::params![run_id, test_id], mock_warning_from_row)?,
      None => stmt.query_map(rusqlite::params![run_id], mock_warning_from_row)?,
    };
    Ok(rows.collect::<rusqlite::Result<Vec<_>>>()?)
  }
}
