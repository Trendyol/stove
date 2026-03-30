use std::sync::{Arc, Mutex, MutexGuard};

use crate::error::Result;
use crate::ingest::PersistedPortalEvent;
use crate::storage::database::Database;
use crate::storage::models::{
  AppSummary, Entry, NewEntry, NewSpan, Run, RunStatus, Snapshot, Span, Test, TestStatus,
};

/// Thread-safe repository for CRUD operations on the `SQLite` database.
///
/// Writes and reads use separate SQLite connections so the UI can keep polling
/// while ingestion is busy. Each side is still serialized through its own mutex
/// because a single `rusqlite::Connection` is not `Sync`.
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

  fn lock_write_db(&self) -> MutexGuard<'_, Database> {
    self.write_db.lock().expect("write database lock poisoned")
  }

  fn lock_read_db(&self) -> MutexGuard<'_, Database> {
    self.read_db.lock().expect("read database lock poisoned")
  }

  // --- Write operations (called from gRPC handler) ---

  pub fn save_run_start(
    &self,
    run_id: &str,
    app_name: &str,
    started_at: &str,
    systems: &[String],
  ) -> Result<()> {
    let db = self.lock_write_db();
    save_run_start_on(db.conn(), run_id, app_name, started_at, systems)?;
    Ok(())
  }

  pub fn save_run_end(
    &self,
    run_id: &str,
    ended_at: &str,
    total_tests: i32,
    passed: i32,
    failed: i32,
    duration_ms: i64,
  ) -> Result<()> {
    let db = self.lock_write_db();
    save_run_end_on(
      db.conn(),
      run_id,
      ended_at,
      total_tests,
      passed,
      failed,
      duration_ms,
    )?;
    Ok(())
  }

  pub fn save_test_start(
    &self,
    run_id: &str,
    test_id: &str,
    test_name: &str,
    spec_name: &str,
    started_at: &str,
  ) -> Result<()> {
    let db = self.lock_write_db();
    save_test_start_on(db.conn(), run_id, test_id, test_name, spec_name, started_at)?;
    Ok(())
  }

  pub fn save_test_end(
    &self,
    run_id: &str,
    test_id: &str,
    status: &str,
    duration_ms: i64,
    error: &str,
    ended_at: &str,
  ) -> Result<()> {
    let db = self.lock_write_db();
    save_test_end_on(
      db.conn(),
      run_id,
      test_id,
      status,
      duration_ms,
      error,
      ended_at,
    )?;
    Ok(())
  }

  pub fn save_entry(&self, entry: &NewEntry) -> Result<()> {
    let db = self.lock_write_db();
    save_entry_on(db.conn(), entry)?;
    Ok(())
  }

  pub fn save_span(&self, span: &NewSpan) -> Result<()> {
    let db = self.lock_write_db();
    save_span_on(db.conn(), span)?;
    Ok(())
  }

  pub fn save_snapshot(
    &self,
    run_id: &str,
    test_id: &str,
    system: &str,
    state_json: &str,
    summary: &str,
  ) -> Result<()> {
    let db = self.lock_write_db();
    save_snapshot_on(db.conn(), run_id, test_id, system, state_json, summary)?;
    Ok(())
  }

  pub fn clear_all(&self) -> Result<()> {
    let db = self.lock_write_db();
    db.conn().execute_batch(
            "DELETE FROM snapshots; DELETE FROM spans; DELETE FROM entries; DELETE FROM tests; DELETE FROM runs;",
        )?;
    Ok(())
  }

  pub fn apply_persisted_events(&self, events: &[PersistedPortalEvent]) -> Result<()> {
    let mut db = self.lock_write_db();
    let tx = db.conn_mut().unchecked_transaction()?;
    for event in events {
      apply_persisted_event(&tx, event)?;
    }
    tx.commit()?;
    Ok(())
  }

  // --- Read operations (called from HTTP handlers) ---

  pub fn get_apps(&self) -> Result<Vec<AppSummary>> {
    let db = self.lock_read_db();
    let mut stmt = db.conn().prepare(
      "SELECT r.app_name, r.id, r.status, (SELECT COUNT(*) FROM runs r2 WHERE r2.app_name = r.app_name)
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
          total_runs: row.get(3)?,
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
            "SELECT id, run_id, test_name, spec_name, started_at, ended_at, status, duration_ms, error FROM tests WHERE run_id = ?1 ORDER BY started_at",
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

fn apply_persisted_event(conn: &rusqlite::Connection, event: &PersistedPortalEvent) -> Result<()> {
  match event {
    PersistedPortalEvent::RunStarted {
      run_id,
      app_name,
      started_at,
      systems,
    } => save_run_start_on(conn, run_id, app_name, started_at, systems),
    PersistedPortalEvent::RunEnded {
      run_id,
      ended_at,
      total_tests,
      passed,
      failed,
      duration_ms,
    } => save_run_end_on(
      conn,
      run_id,
      ended_at,
      *total_tests,
      *passed,
      *failed,
      *duration_ms,
    ),
    PersistedPortalEvent::TestStarted {
      run_id,
      test_id,
      test_name,
      spec_name,
      started_at,
    } => save_test_start_on(conn, run_id, test_id, test_name, spec_name, started_at),
    PersistedPortalEvent::TestEnded {
      run_id,
      test_id,
      status,
      duration_ms,
      error,
      ended_at,
    } => save_test_end_on(
      conn,
      run_id,
      test_id,
      status,
      *duration_ms,
      error.as_deref().unwrap_or_default(),
      ended_at,
    ),
    PersistedPortalEvent::EntryRecorded(entry) => save_entry_on(conn, entry),
    PersistedPortalEvent::SpanRecorded(span) => save_span_on(conn, span),
    PersistedPortalEvent::Snapshot {
      run_id,
      test_id,
      system,
      state_json,
      summary,
    } => save_snapshot_on(conn, run_id, test_id, system, state_json, summary),
  }
}

fn save_run_start_on(
  conn: &rusqlite::Connection,
  run_id: &str,
  app_name: &str,
  started_at: &str,
  systems: &[String],
) -> Result<()> {
  let systems_json = serde_json::to_string(systems)?;
  conn.execute(
    "INSERT OR REPLACE INTO runs (id, app_name, started_at, systems) VALUES (?1, ?2, ?3, ?4)",
    rusqlite::params![run_id, app_name, started_at, systems_json],
  )?;
  Ok(())
}

fn save_run_end_on(
  conn: &rusqlite::Connection,
  run_id: &str,
  ended_at: &str,
  total_tests: i32,
  passed: i32,
  failed: i32,
  duration_ms: i64,
) -> Result<()> {
  let status = if failed > 0 { "FAILED" } else { "PASSED" };
  conn.execute(
    "UPDATE runs SET ended_at = ?1, status = ?2, total_tests = ?3, passed = ?4, failed = ?5, duration_ms = ?6 WHERE id = ?7",
    rusqlite::params![ended_at, status, total_tests, passed, failed, duration_ms, run_id],
  )?;
  Ok(())
}

fn save_test_start_on(
  conn: &rusqlite::Connection,
  run_id: &str,
  test_id: &str,
  test_name: &str,
  spec_name: &str,
  started_at: &str,
) -> Result<()> {
  conn.execute(
    "INSERT OR REPLACE INTO tests (id, run_id, test_name, spec_name, started_at) VALUES (?1, ?2, ?3, ?4, ?5)",
    rusqlite::params![test_id, run_id, test_name, spec_name, started_at],
  )?;
  Ok(())
}

fn save_test_end_on(
  conn: &rusqlite::Connection,
  run_id: &str,
  test_id: &str,
  status: &str,
  duration_ms: i64,
  error: &str,
  ended_at: &str,
) -> Result<()> {
  conn.execute(
    "UPDATE tests SET ended_at = ?1, status = ?2, duration_ms = ?3, error = ?4 WHERE run_id = ?5 AND id = ?6",
    rusqlite::params![ended_at, status, duration_ms, non_empty(error), run_id, test_id],
  )?;
  Ok(())
}

fn save_entry_on(conn: &rusqlite::Connection, entry: &NewEntry) -> Result<()> {
  conn.execute(
    "INSERT INTO entries (run_id, test_id, timestamp, system, action, result, input, output, metadata, expected, actual, error, trace_id) VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?10, ?11, ?12, ?13)",
    rusqlite::params![
      entry.run_id,
      entry.test_id,
      entry.timestamp,
      entry.system,
      entry.action,
      entry.result,
      non_empty(&entry.input),
      non_empty(&entry.output),
      non_empty(&entry.metadata),
      non_empty(&entry.expected),
      non_empty(&entry.actual),
      non_empty(&entry.error),
      non_empty(&entry.trace_id)
    ],
  )?;
  Ok(())
}

fn save_span_on(conn: &rusqlite::Connection, span: &NewSpan) -> Result<()> {
  conn.execute(
    "INSERT INTO spans (run_id, trace_id, span_id, parent_span_id, operation_name, service_name, start_time_nanos, end_time_nanos, status, attributes, exception_type, exception_message, exception_stack_trace) VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?10, ?11, ?12, ?13)",
    rusqlite::params![
      span.run_id,
      span.trace_id,
      span.span_id,
      non_empty(&span.parent_span_id),
      span.operation_name,
      span.service_name,
      span.start_time_nanos,
      span.end_time_nanos,
      span.status,
      non_empty(&span.attributes),
      non_empty(&span.exception_type),
      non_empty(&span.exception_message),
      non_empty(&span.exception_stack_trace)
    ],
  )?;
  Ok(())
}

fn save_snapshot_on(
  conn: &rusqlite::Connection,
  run_id: &str,
  test_id: &str,
  system: &str,
  state_json: &str,
  summary: &str,
) -> Result<()> {
  conn.execute(
    "INSERT INTO snapshots (run_id, test_id, system, state_json, summary) VALUES (?1, ?2, ?3, ?4, ?5)",
    rusqlite::params![run_id, test_id, system, state_json, summary],
  )?;
  Ok(())
}

// --- SQL column constants ---

const RUN_COLUMNS: &str =
  "id, app_name, started_at, ended_at, status, total_tests, passed, failed, duration_ms, systems";
const SPAN_COLUMNS: &str = "id, run_id, trace_id, span_id, parent_span_id, operation_name, service_name, start_time_nanos, end_time_nanos, status, attributes, exception_type, exception_message, exception_stack_trace";

// --- Row-mapping helpers ---

/// Convert empty strings to `None` for optional database fields.
fn non_empty(s: &str) -> Option<&str> {
  if s.is_empty() { None } else { Some(s) }
}

/// Parse a `RunStatus` from a database string, defaulting to `Running`.
fn parse_run_status(s: &str) -> RunStatus {
  s.parse().unwrap_or(RunStatus::Running)
}

/// Parse a `TestStatus` from a database string, defaulting to `Running`.
fn parse_test_status(s: &str) -> TestStatus {
  s.parse().unwrap_or(TestStatus::Running)
}

fn run_from_row(row: &rusqlite::Row<'_>) -> rusqlite::Result<Run> {
  let systems_json: String = row.get(9)?;
  let systems: Vec<String> = serde_json::from_str(&systems_json).unwrap_or_default();
  Ok(Run {
    id: row.get(0)?,
    app_name: row.get(1)?,
    started_at: row.get(2)?,
    ended_at: row.get(3)?,
    status: parse_run_status(&row.get::<_, String>(4)?),
    total_tests: row.get(5)?,
    passed: row.get(6)?,
    failed: row.get(7)?,
    duration_ms: row.get(8)?,
    systems,
  })
}

fn test_from_row(row: &rusqlite::Row<'_>) -> rusqlite::Result<Test> {
  Ok(Test {
    id: row.get(0)?,
    run_id: row.get(1)?,
    test_name: row.get(2)?,
    spec_name: row.get(3)?,
    started_at: row.get(4)?,
    ended_at: row.get(5)?,
    status: parse_test_status(&row.get::<_, String>(6)?),
    duration_ms: row.get(7)?,
    error: row.get(8)?,
  })
}

fn entry_from_row(row: &rusqlite::Row<'_>) -> rusqlite::Result<Entry> {
  Ok(Entry {
    id: row.get(0)?,
    run_id: row.get(1)?,
    test_id: row.get(2)?,
    timestamp: row.get(3)?,
    system: row.get(4)?,
    action: row.get(5)?,
    result: parse_test_status(&row.get::<_, String>(6)?),
    input: row.get(7)?,
    output: row.get(8)?,
    metadata: row.get(9)?,
    expected: row.get(10)?,
    actual: row.get(11)?,
    error: row.get(12)?,
    trace_id: row.get(13)?,
  })
}

fn snapshot_from_row(row: &rusqlite::Row<'_>) -> rusqlite::Result<Snapshot> {
  Ok(Snapshot {
    id: row.get(0)?,
    run_id: row.get(1)?,
    test_id: row.get(2)?,
    system: row.get(3)?,
    state_json: row.get(4)?,
    summary: row.get(5)?,
  })
}

fn span_from_row(row: &rusqlite::Row<'_>) -> rusqlite::Result<Span> {
  Ok(Span {
    id: row.get(0)?,
    run_id: row.get(1)?,
    trace_id: row.get(2)?,
    span_id: row.get(3)?,
    parent_span_id: row.get(4)?,
    operation_name: row.get(5)?,
    service_name: row.get(6)?,
    start_time_nanos: row.get(7)?,
    end_time_nanos: row.get(8)?,
    status: row.get(9)?,
    attributes: row.get(10)?,
    exception_type: row.get(11)?,
    exception_message: row.get(12)?,
    exception_stack_trace: row.get(13)?,
  })
}

#[cfg(test)]
mod tests {
  use super::*;
  use crate::storage::database::Database;

  fn test_repo() -> Repository {
    Repository::new(Database::open(":memory:").unwrap())
  }

  #[test]
  fn full_event_lifecycle() {
    let repo = test_repo();

    repo
      .save_run_start(
        "run-1",
        "product-api",
        "2024-01-01T00:00:00Z",
        &["HTTP".into(), "Kafka".into()],
      )
      .unwrap();

    repo
      .save_test_start(
        "run-1",
        "test-1",
        "should create product",
        "ProductSpec",
        "2024-01-01T00:00:01Z",
      )
      .unwrap();

    repo
      .save_entry(&NewEntry {
        run_id: "run-1".into(),
        test_id: "test-1".into(),
        timestamp: "2024-01-01T00:00:02Z".into(),
        system: "HTTP".into(),
        action: "POST /products".into(),
        result: "PASSED".into(),
        input: r#"{"name":"widget"}"#.into(),
        output: r#"{"id":1}"#.into(),
        metadata: "{}".into(),
        expected: String::new(),
        actual: String::new(),
        error: String::new(),
        trace_id: String::new(),
      })
      .unwrap();

    repo
      .save_span(&NewSpan {
        run_id: "run-1".into(),
        trace_id: "trace-abc".into(),
        span_id: "span-1".into(),
        operation_name: "POST /products".into(),
        service_name: "product-api".into(),
        start_time_nanos: 1_000_000_000,
        end_time_nanos: 1_100_000_000,
        status: "OK".into(),
        attributes: r#"{"http.method":"POST"}"#.into(),
        ..Default::default()
      })
      .unwrap();

    repo
      .save_snapshot(
        "run-1",
        "test-1",
        "Kafka",
        r#"{"consumed":5}"#,
        "5 messages consumed",
      )
      .unwrap();

    repo
      .save_test_end(
        "run-1",
        "test-1",
        "PASSED",
        1500,
        "",
        "2024-01-01T00:00:03Z",
      )
      .unwrap();

    repo
      .save_run_end("run-1", "2024-01-01T00:00:10Z", 1, 1, 0, 10000)
      .unwrap();

    let runs = repo.get_runs(None).unwrap();
    assert_eq!(runs.len(), 1);
    assert_eq!(runs[0].app_name, "product-api");
    assert_eq!(runs[0].status, RunStatus::Passed);
    assert_eq!(runs[0].systems, vec!["HTTP", "Kafka"]);

    let run = repo.get_run("run-1").unwrap().unwrap();
    assert_eq!(run.total_tests, 1);
    assert_eq!(run.passed, 1);

    let tests = repo.get_tests_for_run("run-1").unwrap();
    assert_eq!(tests.len(), 1);
    assert_eq!(tests[0].test_name, "should create product");
    assert_eq!(tests[0].status, TestStatus::Passed);

    let entries = repo.get_entries("run-1", "test-1").unwrap();
    assert_eq!(entries.len(), 1);
    assert_eq!(entries[0].system, "HTTP");
    assert_eq!(entries[0].action, "POST /products");

    let trace = repo.get_trace("trace-abc").unwrap();
    assert_eq!(trace.len(), 1);
    assert_eq!(trace[0].operation_name, "POST /products");

    let snapshots = repo.get_snapshots("run-1", "test-1").unwrap();
    assert_eq!(snapshots.len(), 1);
    assert_eq!(snapshots[0].system, "Kafka");

    let apps = repo.get_apps().unwrap();
    assert_eq!(apps.len(), 1);
    assert_eq!(apps[0].app_name, "product-api");
    assert_eq!(apps[0].total_runs, 1);
  }

  #[test]
  fn get_runs_filters_by_app_name() {
    let repo = test_repo();
    repo
      .save_run_start("run-1", "product-api", "2024-01-01T00:00:00Z", &[])
      .unwrap();
    repo
      .save_run_start("run-2", "order-api", "2024-01-01T00:00:01Z", &[])
      .unwrap();

    let product_runs = repo.get_runs(Some("product-api")).unwrap();
    assert_eq!(product_runs.len(), 1);
    assert_eq!(product_runs[0].app_name, "product-api");

    let all_runs = repo.get_runs(None).unwrap();
    assert_eq!(all_runs.len(), 2);
  }

  #[test]
  fn clear_all_removes_everything() {
    let repo = test_repo();
    repo
      .save_run_start("run-1", "app", "2024-01-01T00:00:00Z", &[])
      .unwrap();
    repo
      .save_test_start("run-1", "test-1", "test", "", "2024-01-01T00:00:01Z")
      .unwrap();

    repo.clear_all().unwrap();

    assert!(repo.get_runs(None).unwrap().is_empty());
    assert!(repo.get_tests_for_run("run-1").unwrap().is_empty());
  }

  #[test]
  fn get_apps_returns_single_latest_run_when_started_at_ties() {
    let repo = test_repo();
    repo
      .save_run_start("run-1", "my-app", "2024-06-01T00:00:00Z", &[])
      .unwrap();
    repo
      .save_run_start("run-2", "my-app", "2024-06-01T00:00:00Z", &[])
      .unwrap();

    let apps = repo.get_apps().unwrap();

    assert_eq!(apps.len(), 1);
    assert_eq!(apps[0].app_name, "my-app");
    assert_eq!(apps[0].latest_run_id, "run-2");
    assert_eq!(apps[0].total_runs, 2);
  }

  #[test]
  fn get_runs_orders_same_timestamp_runs_by_latest_inserted_first() {
    let repo = test_repo();
    repo
      .save_run_start("run-1", "my-app", "2024-06-01T00:00:00Z", &[])
      .unwrap();
    repo
      .save_run_start("run-2", "my-app", "2024-06-01T00:00:00Z", &[])
      .unwrap();

    let runs = repo.get_runs(Some("my-app")).unwrap();

    assert_eq!(runs.len(), 2);
    assert_eq!(runs[0].id, "run-2");
    assert_eq!(runs[1].id, "run-1");
  }

  #[test]
  fn get_spans_for_test_does_not_cross_match_similar_test_ids() {
    let repo = test_repo();
    repo
      .save_run_start("run-1", "my-app", "2024-06-01T00:00:00Z", &[])
      .unwrap();
    repo
      .save_test_start(
        "run-1",
        "test-1",
        "first test",
        "Spec",
        "2024-06-01T00:00:01Z",
      )
      .unwrap();
    repo
      .save_test_start(
        "run-1",
        "test-10",
        "tenth test",
        "Spec",
        "2024-06-01T00:00:02Z",
      )
      .unwrap();
    repo
      .save_span(&NewSpan {
        run_id: "run-1".into(),
        trace_id: "trace-10".into(),
        span_id: "span-10".into(),
        operation_name: "GET /ten".into(),
        service_name: "my-app".into(),
        start_time_nanos: 1_000_000_000,
        end_time_nanos: 1_100_000_000,
        status: "OK".into(),
        attributes: r#"{"x-stove-test-id":"test-10"}"#.into(),
        ..Default::default()
      })
      .unwrap();

    let spans = repo.get_spans_for_test("run-1", "test-1").unwrap();

    assert!(spans.is_empty());
  }
}
