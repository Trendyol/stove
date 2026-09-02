//! Write path for the dashboard repository.
//!
//! All `INSERT` / `UPDATE` SQL lives here, plus the dispatcher that replays a
//! batch of `PersistedDashboardEvent` items inside a single transaction. The
//! free `*_on` functions take a `&rusqlite::Connection` so they can be invoked
//! either against the long-lived write connection or against a transaction.

use super::super::write_models::{
  RunEnd, RunStart, SnapshotWrite, TestEnd, TestStart, WriteOperation,
};
use super::SqliteBackend;
use super::mapping::non_empty;
use crate::error::Result;
use crate::ingest::PersistedDashboardEvent;
use crate::storage::models::NewEntry;
use crate::storage::models::NewMockInteraction;
use crate::storage::models::NewMockWarning;
use crate::storage::models::NewSpan;
use rusqlite::OptionalExtension;
impl SqliteBackend {
  pub fn save_run_start(&self, run: &RunStart<'_>) -> Result<()> {
    let mut db = self.lock_write();
    let tx = db.conn_mut().unchecked_transaction()?;
    save_run_start_on(&tx, run)?;
    tx.commit()?;
    Ok(())
  }

  pub fn save_run_end(&self, run: &RunEnd<'_>, retention_runs_per_app: usize) -> Result<()> {
    let mut db = self.lock_write();
    let tx = db.conn_mut().unchecked_transaction()?;
    save_run_end_on(&tx, run)?;
    prune_completed_runs_on(&tx, run.run_id, retention_runs_per_app)?;
    tx.commit()?;
    Ok(())
  }

  pub fn save_test_start(&self, test: &TestStart<'_>) -> Result<()> {
    let db = self.lock_write();
    save_test_start_on(db.conn(), test)?;
    Ok(())
  }

  pub fn save_test_end(&self, test: &TestEnd<'_>) -> Result<()> {
    let db = self.lock_write();
    save_test_end_on(db.conn(), test)?;
    Ok(())
  }

  pub fn save_entry(&self, entry: &NewEntry) -> Result<()> {
    let db = self.lock_write();
    save_entry_on(db.conn(), entry)?;
    Ok(())
  }

  pub fn save_span(&self, span: &NewSpan) -> Result<()> {
    let db = self.lock_write();
    save_span_on(db.conn(), span)?;
    Ok(())
  }

  pub fn save_snapshot(&self, snapshot: &SnapshotWrite<'_>) -> Result<()> {
    let db = self.lock_write();
    save_snapshot_on(db.conn(), snapshot)?;
    Ok(())
  }

  pub fn save_mock_interaction(&self, interaction: &NewMockInteraction) -> Result<()> {
    let db = self.lock_write();
    save_mock_interaction_on(db.conn(), interaction)?;
    Ok(())
  }

  pub fn save_mock_warning(&self, warning: &NewMockWarning) -> Result<()> {
    let db = self.lock_write();
    save_mock_warning_on(db.conn(), warning)?;
    Ok(())
  }

  pub fn clear_all(&self) -> Result<()> {
    let db = self.lock_write();
    db.conn().execute_batch(
      "DELETE FROM mock_warnings; DELETE FROM mock_interactions; DELETE FROM snapshots; DELETE FROM spans; DELETE FROM entries; DELETE FROM tests; DELETE FROM runs;",
    )?;
    Ok(())
  }

  pub fn apply_persisted_events(
    &self,
    events: &[PersistedDashboardEvent],
    retention_runs_per_app: usize,
  ) -> Result<()> {
    let mut db = self.lock_write();
    let tx = db.conn_mut().unchecked_transaction()?;
    for event in events {
      apply_persisted_event(&tx, event, retention_runs_per_app)?;
    }
    tx.commit()?;
    Ok(())
  }
}

fn apply_persisted_event(
  conn: &rusqlite::Connection,
  event: &PersistedDashboardEvent,
  retention_runs_per_app: usize,
) -> Result<()> {
  match WriteOperation::from(event) {
    WriteOperation::RunStarted(run) => save_run_start_on(conn, &run),
    WriteOperation::RunEnded(run) => {
      save_run_end_on(conn, &run)?;
      prune_completed_runs_on(conn, run.run_id, retention_runs_per_app)
    }
    WriteOperation::TestStarted(test) => save_test_start_on(conn, &test),
    WriteOperation::TestEnded(test) => save_test_end_on(conn, &test),
    WriteOperation::Entry(entry) => save_entry_on(conn, entry),
    WriteOperation::Span(span) => save_span_on(conn, span),
    WriteOperation::Snapshot(snapshot) => save_snapshot_on(conn, &snapshot),
    WriteOperation::MockInteraction(interaction) => save_mock_interaction_on(conn, interaction),
    WriteOperation::MockWarning(warning) => save_mock_warning_on(conn, warning),
  }
}

fn save_run_start_on(conn: &rusqlite::Connection, run: &RunStart<'_>) -> Result<()> {
  let systems_json = serde_json::to_string(run.systems)?;
  let metadata_json = serde_json::to_string(run.metadata)?;
  conn.execute(
    "INSERT OR REPLACE INTO runs (id, app_name, started_at, stove_version, systems, metadata) VALUES (?1, ?2, ?3, ?4, ?5, ?6)",
    rusqlite::params![
      run.run_id,
      run.app_name,
      run.started_at,
      run.stove_version,
      systems_json,
      metadata_json
    ],
  )?;
  Ok(())
}

fn prune_completed_runs_on(
  conn: &rusqlite::Connection,
  completed_run_id: &str,
  retention_runs_per_app: usize,
) -> Result<()> {
  if retention_runs_per_app == 0 {
    return Ok(());
  }

  let app_name = conn
    .query_row(
      "SELECT app_name FROM runs WHERE id = ?1",
      rusqlite::params![completed_run_id],
      |row| row.get::<_, String>(0),
    )
    .optional()?;
  let Some(app_name) = app_name else {
    return Ok(());
  };
  prune_completed_runs_for_app_on(conn, &app_name, retention_runs_per_app)
}

pub(super) fn prune_completed_runs_for_app_on(
  conn: &rusqlite::Connection,
  app_name: &str,
  retention_runs_per_app: usize,
) -> Result<()> {
  if retention_runs_per_app == 0 {
    return Ok(());
  }
  let offset = i64::try_from(retention_runs_per_app).unwrap_or(i64::MAX);
  let mut stmt = conn.prepare(
    "SELECT id
      FROM runs
      WHERE app_name = ?1 AND status <> 'RUNNING'
      ORDER BY started_at DESC, ended_at DESC, rowid DESC
      LIMIT -1 OFFSET ?2",
  )?;
  let expired_run_ids = stmt
    .query_map(rusqlite::params![app_name, offset], |row| {
      row.get::<_, String>(0)
    })?
    .collect::<rusqlite::Result<Vec<_>>>()?;
  drop(stmt);

  delete_runs_on(conn, &expired_run_ids)
}

pub(super) fn delete_runs_on(conn: &rusqlite::Connection, run_ids: &[String]) -> Result<()> {
  if run_ids.is_empty() {
    return Ok(());
  }
  let run_ids = serde_json::to_string(run_ids)?;
  for table in [
    "mock_warnings",
    "mock_interactions",
    "snapshots",
    "spans",
    "entries",
    "tests",
  ] {
    conn.execute(
      &format!("DELETE FROM {table} WHERE run_id IN (SELECT value FROM json_each(?1))"),
      rusqlite::params![run_ids],
    )?;
  }
  conn.execute(
    "DELETE FROM runs WHERE id IN (SELECT value FROM json_each(?1))",
    rusqlite::params![run_ids],
  )?;
  Ok(())
}

fn save_run_end_on(conn: &rusqlite::Connection, run: &RunEnd<'_>) -> Result<()> {
  let status = run.status();
  conn.execute(
    "UPDATE runs SET ended_at = ?1, status = ?2, total_tests = ?3, passed = ?4, failed = ?5, duration_ms = ?6 WHERE id = ?7",
    rusqlite::params![
      run.ended_at,
      status.to_string(),
      run.total_tests,
      run.passed,
      run.failed,
      run.duration_ms,
      run.run_id
    ],
  )?;
  Ok(())
}

fn save_test_start_on(conn: &rusqlite::Connection, test: &TestStart<'_>) -> Result<()> {
  let test_path_json = serde_json::to_string(test.test_path)?;
  conn.execute(
    "INSERT OR REPLACE INTO tests (id, run_id, test_name, spec_name, test_path, started_at) VALUES (?1, ?2, ?3, ?4, ?5, ?6)",
    rusqlite::params![
      test.test_id,
      test.run_id,
      test.test_name,
      test.spec_name,
      test_path_json,
      test.started_at
    ],
  )?;
  Ok(())
}

fn save_test_end_on(conn: &rusqlite::Connection, test: &TestEnd<'_>) -> Result<()> {
  conn.execute(
    "UPDATE tests SET ended_at = ?1, status = ?2, duration_ms = ?3, error = ?4 WHERE run_id = ?5 AND id = ?6",
    rusqlite::params![
      test.ended_at,
      test.status,
      test.duration_ms,
      non_empty(test.error),
      test.run_id,
      test.test_id
    ],
  )?;
  Ok(())
}

fn save_entry_on(conn: &rusqlite::Connection, entry: &NewEntry) -> Result<()> {
  conn.execute(
    "INSERT INTO entries (run_id, test_id, timestamp, system, action, result, input, output, metadata, expected, actual, error, trace_id, assertion_id) VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?10, ?11, ?12, ?13, ?14)",
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
      non_empty(&entry.trace_id),
      entry.assertion_id
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

fn save_snapshot_on(conn: &rusqlite::Connection, snapshot: &SnapshotWrite<'_>) -> Result<()> {
  conn.execute(
    "INSERT INTO snapshots (run_id, test_id, system, state_json, summary, captured_at, trigger_kind) VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7)",
    rusqlite::params![
      snapshot.run_id,
      snapshot.test_id,
      snapshot.system,
      snapshot.state_json,
      snapshot.summary,
      non_empty(snapshot.captured_at),
      snapshot.trigger
    ],
  )?;
  Ok(())
}

fn save_mock_interaction_on(
  conn: &rusqlite::Connection,
  interaction: &NewMockInteraction,
) -> Result<()> {
  conn.execute(
    "INSERT INTO mock_interactions (run_id, test_id, timestamp, system, protocol, method, target, matched, stub_id, attribution, request_body, request_body_truncated, response_body, response_body_truncated, status, latency_ms, near_misses, trace_id, scenario_name, scenario_state, next_scenario_state, configured_delay_ms, fault, client_deadline_ms) VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?10, ?11, ?12, ?13, ?14, ?15, ?16, ?17, ?18, ?19, ?20, ?21, ?22, ?23, ?24)",
    rusqlite::params![
      interaction.run_id,
      interaction.test_id,
      interaction.timestamp,
      interaction.system,
      interaction.protocol,
      interaction.method,
      interaction.target,
      interaction.matched,
      interaction.stub_id,
      interaction.attribution,
      non_empty(&interaction.request_body),
      interaction.request_body_truncated,
      non_empty(&interaction.response_body),
      interaction.response_body_truncated,
      interaction.status,
      interaction.latency_ms,
      non_empty(&interaction.near_misses),
      interaction.trace_id,
      interaction.scenario_name,
      interaction.scenario_state,
      interaction.next_scenario_state,
      interaction.configured_delay_ms,
      interaction.fault,
      interaction.client_deadline_ms
    ],
  )?;
  Ok(())
}

fn save_mock_warning_on(conn: &rusqlite::Connection, warning: &NewMockWarning) -> Result<()> {
  conn.execute(
    "INSERT INTO mock_warnings (run_id, test_id, timestamp, system, kind, message, stub_id, target) VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8)",
    rusqlite::params![
      warning.run_id,
      warning.test_id,
      warning.timestamp,
      warning.system,
      warning.kind,
      warning.message,
      warning.stub_id,
      warning.target
    ],
  )?;
  Ok(())
}
