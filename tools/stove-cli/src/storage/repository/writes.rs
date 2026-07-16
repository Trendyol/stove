//! Write path for the dashboard repository.
//!
//! All `INSERT` / `UPDATE` SQL lives here, plus the dispatcher that replays a
//! batch of `PersistedDashboardEvent` items inside a single transaction. The
//! free `*_on` functions take a `&rusqlite::Connection` so they can be invoked
//! either against the long-lived write connection or against a transaction.

use super::Repository;
use super::sql::non_empty;
use crate::error::Result;
use crate::ingest::PersistedDashboardEvent;
use crate::storage::models::NewEntry;
use crate::storage::models::NewMockInteraction;
use crate::storage::models::NewMockWarning;
use crate::storage::models::NewSpan;
use crate::storage::models::RunStatus;

impl Repository {
  pub fn save_run_start(
    &self,
    run_id: &str,
    app_name: &str,
    started_at: &str,
    systems: &[String],
  ) -> Result<()> {
    self.save_run_start_with_version(
      run_id, app_name, started_at, /*stove_version*/ None, systems,
    )
  }

  pub fn save_run_start_with_version(
    &self,
    run_id: &str,
    app_name: &str,
    started_at: &str,
    stove_version: Option<&str>,
    systems: &[String],
  ) -> Result<()> {
    let db = self.lock_write_db();
    save_run_start_on(
      db.conn(),
      run_id,
      app_name,
      started_at,
      stove_version,
      systems,
    )?;
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
    test_path: &[String],
    started_at: &str,
  ) -> Result<()> {
    let db = self.lock_write_db();
    save_test_start_on(
      db.conn(),
      run_id,
      test_id,
      test_name,
      spec_name,
      test_path,
      started_at,
    )?;
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
    save_snapshot_on(
      db.conn(),
      run_id,
      test_id,
      system,
      state_json,
      summary,
      "",
      "TEST_END",
    )?;
    Ok(())
  }

  pub fn save_mock_interaction(&self, interaction: &NewMockInteraction) -> Result<()> {
    let db = self.lock_write_db();
    save_mock_interaction_on(db.conn(), interaction)?;
    Ok(())
  }

  pub fn save_mock_warning(&self, warning: &NewMockWarning) -> Result<()> {
    let db = self.lock_write_db();
    save_mock_warning_on(db.conn(), warning)?;
    Ok(())
  }

  pub fn clear_all(&self) -> Result<()> {
    let db = self.lock_write_db();
    db.conn().execute_batch(
      "DELETE FROM mock_warnings; DELETE FROM mock_interactions; DELETE FROM snapshots; DELETE FROM spans; DELETE FROM entries; DELETE FROM tests; DELETE FROM runs;",
    )?;
    Ok(())
  }

  pub fn apply_persisted_events(&self, events: &[PersistedDashboardEvent]) -> Result<()> {
    let mut db = self.lock_write_db();
    let tx = db.conn_mut().unchecked_transaction()?;
    for event in events {
      apply_persisted_event(&tx, event)?;
    }
    tx.commit()?;
    Ok(())
  }
}

fn apply_persisted_event(
  conn: &rusqlite::Connection,
  event: &PersistedDashboardEvent,
) -> Result<()> {
  match event {
    PersistedDashboardEvent::RunStarted {
      run_id,
      app_name,
      started_at,
      stove_version,
      systems,
    } => save_run_start_on(
      conn,
      run_id,
      app_name,
      started_at,
      stove_version.as_deref(),
      systems,
    ),
    PersistedDashboardEvent::RunEnded {
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
    PersistedDashboardEvent::TestStarted {
      run_id,
      test_id,
      test_name,
      spec_name,
      test_path,
      started_at,
    } => save_test_start_on(
      conn, run_id, test_id, test_name, spec_name, test_path, started_at,
    ),
    PersistedDashboardEvent::TestEnded {
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
    PersistedDashboardEvent::EntryRecorded(entry) => save_entry_on(conn, entry),
    PersistedDashboardEvent::SpanRecorded(span) => save_span_on(conn, span),
    PersistedDashboardEvent::Snapshot {
      run_id,
      test_id,
      system,
      state_json,
      summary,
      captured_at,
      trigger,
    } => save_snapshot_on(
      conn,
      run_id,
      test_id,
      system,
      state_json,
      summary,
      captured_at,
      trigger,
    ),
    PersistedDashboardEvent::MockInteraction(interaction) => {
      save_mock_interaction_on(conn, interaction)
    }
    PersistedDashboardEvent::MockWarning(warning) => save_mock_warning_on(conn, warning),
  }
}

fn save_run_start_on(
  conn: &rusqlite::Connection,
  run_id: &str,
  app_name: &str,
  started_at: &str,
  stove_version: Option<&str>,
  systems: &[String],
) -> Result<()> {
  let systems_json = serde_json::to_string(systems)?;
  conn.execute(
    "INSERT OR REPLACE INTO runs (id, app_name, started_at, stove_version, systems) VALUES (?1, ?2, ?3, ?4, ?5)",
    rusqlite::params![run_id, app_name, started_at, stove_version, systems_json],
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
  let status = if failed > 0 {
    RunStatus::Failed
  } else {
    RunStatus::Passed
  };
  conn.execute(
    "UPDATE runs SET ended_at = ?1, status = ?2, total_tests = ?3, passed = ?4, failed = ?5, duration_ms = ?6 WHERE id = ?7",
    rusqlite::params![ended_at, status.to_string(), total_tests, passed, failed, duration_ms, run_id],
  )?;
  Ok(())
}

fn save_test_start_on(
  conn: &rusqlite::Connection,
  run_id: &str,
  test_id: &str,
  test_name: &str,
  spec_name: &str,
  test_path: &[String],
  started_at: &str,
) -> Result<()> {
  let test_path_json = serde_json::to_string(test_path)?;
  conn.execute(
    "INSERT OR REPLACE INTO tests (id, run_id, test_name, spec_name, test_path, started_at) VALUES (?1, ?2, ?3, ?4, ?5, ?6)",
    rusqlite::params![test_id, run_id, test_name, spec_name, test_path_json, started_at],
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

#[allow(clippy::too_many_arguments)]
fn save_snapshot_on(
  conn: &rusqlite::Connection,
  run_id: &str,
  test_id: &str,
  system: &str,
  state_json: &str,
  summary: &str,
  captured_at: &str,
  trigger: &str,
) -> Result<()> {
  conn.execute(
    "INSERT INTO snapshots (run_id, test_id, system, state_json, summary, captured_at, trigger_kind) VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7)",
    rusqlite::params![run_id, test_id, system, state_json, summary, non_empty(captured_at), trigger],
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
