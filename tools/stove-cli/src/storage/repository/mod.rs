//! Thread-safe SQLite-backed storage for dashboard runs, tests, entries,
//! spans, and snapshots.
//!
//! Writes and reads use separate `SQLite` connections so the UI can keep
//! polling while ingestion is busy. Each side is still serialized through its
//! own mutex because a single `rusqlite::Connection` is not `Sync`.
//!
//! Implementation is split across this module: `mod.rs` owns the struct,
//! constructors, lock helpers, and read-side methods; `writes.rs` owns the
//! write-side methods plus the batched event replay; `sql.rs` owns the SQL
//! column lists and row→struct converters shared by both.

mod sql;
mod writes;

use std::sync::Arc;
use std::sync::Mutex;
use std::sync::MutexGuard;

use self::sql::LOG_COLUMNS;
use self::sql::RUN_COLUMNS;
use self::sql::SPAN_COLUMNS;
use self::sql::entry_from_row;
use self::sql::log_from_row;
use self::sql::parse_run_status;
use self::sql::run_from_row;
use self::sql::snapshot_from_row;
use self::sql::span_from_row;
use self::sql::test_from_row;
use crate::error::Result;
use crate::storage::database::Database;
use crate::storage::models::AppSummary;
use crate::storage::models::Entry;
use crate::storage::models::LogQuery;
use crate::storage::models::LogRecord;
use crate::storage::models::Run;
use crate::storage::models::Snapshot;
use crate::storage::models::Span;
use crate::storage::models::Test;

pub struct Repository {
  write_db: Arc<Mutex<Database>>,
  read_db: Arc<Mutex<Database>>,
}

impl Repository {
  pub fn new(db: Database) -> Self {
    let read_db = db
      .open_peer()
      .expect("peer database connection should open for repository reads");
    Self {
      write_db: Arc::new(Mutex::new(db)),
      read_db: Arc::new(Mutex::new(read_db)),
    }
  }

  pub(super) fn lock_write_db(&self) -> MutexGuard<'_, Database> {
    self.write_db.lock().expect("write database lock poisoned")
  }

  fn lock_read_db(&self) -> MutexGuard<'_, Database> {
    self.read_db.lock().expect("read database lock poisoned")
  }

  pub fn get_apps(&self) -> Result<Vec<AppSummary>> {
    let db = self.lock_read_db();
    let mut stmt = db.conn().prepare(
      "SELECT r.app_name, r.id, r.status, r.stove_version, (SELECT COUNT(*) FROM runs r2 WHERE r2.app_name = r.app_name)
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
    let rows = stmt
      .query_map([], |row| {
        Ok(AppSummary {
          app_name: row.get(0)?,
          latest_run_id: row.get(1)?,
          latest_status: parse_run_status(&row.get::<_, String>(2)?),
          stove_version: row.get(3)?,
          total_runs: row.get(4)?,
        })
      })?
      .filter_map(|r| r.ok())
      .collect();
    Ok(rows)
  }

  pub fn get_runs(&self, app_name: Option<&str>) -> Result<Vec<Run>> {
    let db = self.lock_read_db();
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
    Ok(rows.filter_map(|r| r.ok()).collect())
  }

  pub fn get_run(&self, run_id: &str) -> Result<Option<Run>> {
    let db = self.lock_read_db();
    let sql = format!("SELECT {RUN_COLUMNS} FROM runs WHERE id = ?1");
    let mut stmt = db.conn().prepare(&sql)?;
    let mut rows = stmt.query_map(rusqlite::params![run_id], run_from_row)?;
    Ok(rows.next().and_then(|r| r.ok()))
  }

  pub fn get_tests_for_run(&self, run_id: &str) -> Result<Vec<Test>> {
    let db = self.lock_read_db();
    let mut stmt = db.conn().prepare(
            "SELECT id, run_id, test_name, spec_name, test_path, started_at, ended_at, status, duration_ms, error FROM tests WHERE run_id = ?1 ORDER BY started_at",
        )?;
    let rows = stmt
      .query_map(rusqlite::params![run_id], test_from_row)?
      .filter_map(|r| r.ok())
      .collect();
    Ok(rows)
  }

  pub fn get_entries(&self, run_id: &str, test_id: &str) -> Result<Vec<Entry>> {
    let db = self.lock_read_db();
    let mut stmt = db.conn().prepare(
            "SELECT id, run_id, test_id, timestamp, system, action, result, input, output, metadata, expected, actual, error, trace_id FROM entries WHERE run_id = ?1 AND test_id = ?2 ORDER BY timestamp",
        )?;
    let rows = stmt
      .query_map(rusqlite::params![run_id, test_id], entry_from_row)?
      .filter_map(|r| r.ok())
      .collect();
    Ok(rows)
  }

  pub fn get_spans_for_test(&self, run_id: &str, test_id: &str) -> Result<Vec<Span>> {
    let db = self.lock_read_db();
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
    let rows = stmt
      .query_map(rusqlite::params![run_id, test_id], span_from_row)?
      .filter_map(|r| r.ok())
      .collect();
    Ok(rows)
  }

  pub fn get_trace(&self, trace_id: &str) -> Result<Vec<Span>> {
    let db = self.lock_read_db();
    let sql =
      format!("SELECT {SPAN_COLUMNS} FROM spans WHERE trace_id = ?1 ORDER BY start_time_nanos");
    let mut stmt = db.conn().prepare(&sql)?;
    let rows = stmt
      .query_map(rusqlite::params![trace_id], span_from_row)?
      .filter_map(|r| r.ok())
      .collect();
    Ok(rows)
  }

  pub fn get_logs_for_test(
    &self,
    run_id: &str,
    test_id: &str,
    query: &LogQuery,
  ) -> Result<Vec<LogRecord>> {
    self.query_logs(
      "run_id = ? AND test_id = ?",
      vec![
        rusqlite::types::Value::Text(run_id.to_string()),
        rusqlite::types::Value::Text(test_id.to_string()),
      ],
      query,
    )
  }

  pub fn get_logs_for_run(&self, run_id: &str, query: &LogQuery) -> Result<Vec<LogRecord>> {
    self.query_logs(
      "run_id = ?",
      vec![rusqlite::types::Value::Text(run_id.to_string())],
      query,
    )
  }

  pub fn get_logs_for_trace(&self, trace_id: &str, query: &LogQuery) -> Result<Vec<LogRecord>> {
    self.query_logs(
      "trace_id = ?",
      vec![rusqlite::types::Value::Text(trace_id.to_string())],
      query,
    )
  }

  fn query_logs(
    &self,
    base_filter: &str,
    mut params: Vec<rusqlite::types::Value>,
    query: &LogQuery,
  ) -> Result<Vec<LogRecord>> {
    let db = self.lock_read_db();
    let mut filters = vec![base_filter.to_string()];
    if let Some(level) = query.level.as_deref().filter(|value| !value.is_empty()) {
      filters.push("severity_text = ?".to_string());
      params.push(rusqlite::types::Value::Text(level.to_ascii_uppercase()));
    }
    if let Some(min_severity) = query.min_severity {
      filters.push("severity_number >= ?".to_string());
      params.push(rusqlite::types::Value::Integer(min_severity.into()));
    }
    if let Some(logger) = query.logger.as_deref().filter(|value| !value.is_empty()) {
      filters.push("logger LIKE ?".to_string());
      params.push(rusqlite::types::Value::Text(format!("%{logger}%")));
    }
    if let Some(thread) = query.thread.as_deref().filter(|value| !value.is_empty()) {
      filters.push("thread = ?".to_string());
      params.push(rusqlite::types::Value::Text(thread.to_string()));
    }
    if let Some(search) = query.q.as_deref().filter(|value| !value.is_empty()) {
      filters.push("(body LIKE ? OR logger LIKE ? OR thread LIKE ? OR exception_message LIKE ? OR attributes LIKE ?)".to_string());
      let value = rusqlite::types::Value::Text(format!("%{search}%"));
      params.extend([
        value.clone(),
        value.clone(),
        value.clone(),
        value.clone(),
        value,
      ]);
    }
    if let Some(cursor) = query.cursor {
      filters.push("id > ?".to_string());
      params.push(rusqlite::types::Value::Integer(cursor));
    }
    params.push(rusqlite::types::Value::Integer(
      query.limit.clamp(1, 2_000) as i64,
    ));
    let sql = format!(
      "SELECT {LOG_COLUMNS} FROM logs WHERE {} ORDER BY timestamp, id LIMIT ?",
      filters.join(" AND ")
    );
    let mut stmt = db.conn().prepare(&sql)?;
    let rows = stmt
      .query_map(rusqlite::params_from_iter(params.iter()), log_from_row)?
      .filter_map(|r| r.ok())
      .collect();
    Ok(rows)
  }

  pub fn get_snapshots(&self, run_id: &str, test_id: &str) -> Result<Vec<Snapshot>> {
    let db = self.lock_read_db();
    let mut stmt = db.conn().prepare(
            "SELECT id, run_id, test_id, system, state_json, summary FROM snapshots WHERE run_id = ?1 AND test_id = ?2",
        )?;
    let rows = stmt
      .query_map(rusqlite::params![run_id, test_id], snapshot_from_row)?
      .filter_map(|r| r.ok())
      .collect();
    Ok(rows)
  }
}

#[cfg(test)]
mod tests;
