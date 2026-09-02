use postgres::GenericClient;

use crate::error::Result;
use crate::ingest::PersistedDashboardEvent;
use crate::storage::models::{NewEntry, NewMockInteraction, NewMockWarning, NewSpan};
use crate::storage::repository::write_models::{
  RunEnd, RunStart, SnapshotWrite, TestEnd, TestStart, WriteOperation,
};

use super::PostgresBackend;

impl PostgresBackend {
  pub fn save_run_start(&self, run: &RunStart<'_>) -> Result<()> {
    let mut client = self.lock_write();
    let mut tx = client.transaction()?;
    save_run_start_on(&mut tx, run)?;
    tx.commit()?;
    Ok(())
  }

  pub fn save_run_end(&self, run: &RunEnd<'_>, retention: usize) -> Result<()> {
    let mut client = self.lock_write();
    let mut tx = client.transaction()?;
    save_run_end_on(&mut tx, run)?;
    prune_for_completed_run(&mut tx, run.run_id, retention)?;
    tx.commit()?;
    Ok(())
  }

  pub fn save_test_start(&self, test: &TestStart<'_>) -> Result<()> {
    let mut client = self.lock_write();
    save_test_start_on(&mut *client, test)
  }

  pub fn save_test_end(&self, test: &TestEnd<'_>) -> Result<()> {
    let mut client = self.lock_write();
    save_test_end_on(&mut *client, test)
  }

  pub fn save_entry(&self, entry: &NewEntry) -> Result<()> {
    save_entry_on(&mut *self.lock_write(), entry)
  }

  pub fn save_span(&self, span: &NewSpan) -> Result<()> {
    save_span_on(&mut *self.lock_write(), span)
  }

  pub fn save_snapshot(&self, snapshot: &SnapshotWrite<'_>) -> Result<()> {
    save_snapshot_on(&mut *self.lock_write(), snapshot)
  }

  pub fn save_mock_interaction(&self, interaction: &NewMockInteraction) -> Result<()> {
    save_mock_interaction_on(&mut *self.lock_write(), interaction)
  }

  pub fn save_mock_warning(&self, warning: &NewMockWarning) -> Result<()> {
    save_mock_warning_on(&mut *self.lock_write(), warning)
  }

  pub fn clear_all(&self) -> Result<()> {
    self.lock_write().execute("DELETE FROM runs", &[])?;
    Ok(())
  }

  pub fn apply_persisted_events(
    &self,
    events: &[PersistedDashboardEvent],
    retention: usize,
  ) -> Result<()> {
    let mut client = self.lock_write();
    let mut tx = client.transaction()?;
    for event in events {
      apply_event(&mut tx, event, retention)?;
    }
    tx.commit()?;
    Ok(())
  }
}

fn apply_event<C: GenericClient>(
  client: &mut C,
  event: &PersistedDashboardEvent,
  retention: usize,
) -> Result<()> {
  match WriteOperation::from(event) {
    WriteOperation::RunStarted(run) => save_run_start_on(client, &run),
    WriteOperation::RunEnded(run) => {
      save_run_end_on(client, &run)?;
      prune_for_completed_run(client, run.run_id, retention)
    }
    WriteOperation::TestStarted(test) => save_test_start_on(client, &test),
    WriteOperation::TestEnded(test) => save_test_end_on(client, &test),
    WriteOperation::Entry(entry) => save_entry_on(client, entry),
    WriteOperation::Span(span) => save_span_on(client, span),
    WriteOperation::Snapshot(snapshot) => save_snapshot_on(client, &snapshot),
    WriteOperation::MockInteraction(interaction) => save_mock_interaction_on(client, interaction),
    WriteOperation::MockWarning(warning) => save_mock_warning_on(client, warning),
  }
}

fn save_run_start_on<C: GenericClient>(client: &mut C, run: &RunStart<'_>) -> Result<()> {
  let systems = serde_json::to_string(run.systems)?;
  let metadata = serde_json::to_string(run.metadata)?;
  client.execute(
    "INSERT INTO runs (id, app_name, started_at, stove_version, systems, metadata)
     VALUES ($1, $2, $3, $4, $5, $6::text::jsonb)
     ON CONFLICT (id) DO UPDATE SET app_name = EXCLUDED.app_name,
       started_at = EXCLUDED.started_at, stove_version = EXCLUDED.stove_version,
       systems = EXCLUDED.systems, metadata = EXCLUDED.metadata",
    &[
      &run.run_id,
      &run.app_name,
      &run.started_at,
      &run.stove_version,
      &systems,
      &metadata,
    ],
  )?;
  Ok(())
}

fn save_run_end_on<C: GenericClient>(client: &mut C, run: &RunEnd<'_>) -> Result<()> {
  let status = run.status().to_string();
  client.execute(
    "UPDATE runs SET ended_at = $1, status = $2, total_tests = $3, passed = $4,
       failed = $5, duration_ms = $6 WHERE id = $7",
    &[
      &run.ended_at,
      &status,
      &run.total_tests,
      &run.passed,
      &run.failed,
      &run.duration_ms,
      &run.run_id,
    ],
  )?;
  Ok(())
}

fn save_test_start_on<C: GenericClient>(client: &mut C, test: &TestStart<'_>) -> Result<()> {
  let test_path = serde_json::to_string(test.test_path)?;
  client.execute(
    "INSERT INTO tests (id, run_id, test_name, spec_name, test_path, started_at)
     VALUES ($1, $2, $3, $4, $5, $6)
     ON CONFLICT (run_id, id) DO UPDATE SET test_name = EXCLUDED.test_name,
       spec_name = EXCLUDED.spec_name, test_path = EXCLUDED.test_path,
       started_at = EXCLUDED.started_at",
    &[
      &test.test_id,
      &test.run_id,
      &test.test_name,
      &test.spec_name,
      &test_path,
      &test.started_at,
    ],
  )?;
  Ok(())
}

fn save_test_end_on<C: GenericClient>(client: &mut C, test: &TestEnd<'_>) -> Result<()> {
  client.execute(
    "UPDATE tests SET ended_at = $1, status = $2, duration_ms = $3, error = $4
      WHERE run_id = $5 AND id = $6",
    &[
      &test.ended_at,
      &test.status,
      &test.duration_ms,
      &non_empty(test.error),
      &test.run_id,
      &test.test_id,
    ],
  )?;
  Ok(())
}

fn save_entry_on<C: GenericClient>(client: &mut C, entry: &NewEntry) -> Result<()> {
  client.execute(
    "INSERT INTO entries (run_id, test_id, timestamp, system, action, result, input, output,
      metadata, expected, actual, error, trace_id, assertion_id)
     VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $12, $13, $14)",
    &[
      &entry.run_id,
      &entry.test_id,
      &entry.timestamp,
      &entry.system,
      &entry.action,
      &entry.result,
      &non_empty(&entry.input),
      &non_empty(&entry.output),
      &non_empty(&entry.metadata),
      &non_empty(&entry.expected),
      &non_empty(&entry.actual),
      &non_empty(&entry.error),
      &non_empty(&entry.trace_id),
      &entry.assertion_id,
    ],
  )?;
  Ok(())
}

fn save_span_on<C: GenericClient>(client: &mut C, span: &NewSpan) -> Result<()> {
  client.execute(
    "INSERT INTO spans (run_id, trace_id, span_id, parent_span_id, operation_name, service_name,
      start_time_nanos, end_time_nanos, status, attributes, exception_type, exception_message,
      exception_stack_trace) VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $12, $13)",
    &[
      &span.run_id,
      &span.trace_id,
      &span.span_id,
      &non_empty(&span.parent_span_id),
      &span.operation_name,
      &span.service_name,
      &span.start_time_nanos,
      &span.end_time_nanos,
      &span.status,
      &non_empty(&span.attributes),
      &non_empty(&span.exception_type),
      &non_empty(&span.exception_message),
      &non_empty(&span.exception_stack_trace),
    ],
  )?;
  Ok(())
}

fn save_snapshot_on<C: GenericClient>(client: &mut C, snapshot: &SnapshotWrite<'_>) -> Result<()> {
  client.execute(
    "INSERT INTO snapshots (run_id, test_id, system, state_json, summary, captured_at, trigger_kind)
     VALUES ($1, $2, $3, $4, $5, $6, $7)",
    &[
      &snapshot.run_id,
      &snapshot.test_id,
      &snapshot.system,
      &snapshot.state_json,
      &snapshot.summary,
      &non_empty(snapshot.captured_at),
      &snapshot.trigger,
    ],
  )?;
  Ok(())
}

fn save_mock_interaction_on<C: GenericClient>(
  client: &mut C,
  interaction: &NewMockInteraction,
) -> Result<()> {
  client.execute(
    "INSERT INTO mock_interactions (run_id, test_id, timestamp, system, protocol, method, target,
      matched, stub_id, attribution, request_body, request_body_truncated, response_body,
      response_body_truncated, status, latency_ms, near_misses, trace_id, scenario_name,
      scenario_state, next_scenario_state, configured_delay_ms, fault, client_deadline_ms)
     VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $12, $13, $14, $15,
             $16, $17, $18, $19, $20, $21, $22, $23, $24)",
    &[
      &interaction.run_id,
      &interaction.test_id,
      &interaction.timestamp,
      &interaction.system,
      &interaction.protocol,
      &interaction.method,
      &interaction.target,
      &interaction.matched,
      &interaction.stub_id,
      &interaction.attribution,
      &non_empty(&interaction.request_body),
      &interaction.request_body_truncated,
      &non_empty(&interaction.response_body),
      &interaction.response_body_truncated,
      &interaction.status,
      &interaction.latency_ms,
      &non_empty(&interaction.near_misses),
      &interaction.trace_id,
      &interaction.scenario_name,
      &interaction.scenario_state,
      &interaction.next_scenario_state,
      &interaction.configured_delay_ms,
      &interaction.fault,
      &interaction.client_deadline_ms,
    ],
  )?;
  Ok(())
}

fn save_mock_warning_on<C: GenericClient>(client: &mut C, warning: &NewMockWarning) -> Result<()> {
  client.execute(
    "INSERT INTO mock_warnings (run_id, test_id, timestamp, system, kind, message, stub_id, target)
     VALUES ($1, $2, $3, $4, $5, $6, $7, $8)",
    &[
      &warning.run_id,
      &warning.test_id,
      &warning.timestamp,
      &warning.system,
      &warning.kind,
      &warning.message,
      &warning.stub_id,
      &warning.target,
    ],
  )?;
  Ok(())
}

fn prune_for_completed_run<C: GenericClient>(
  client: &mut C,
  run_id: &str,
  retention: usize,
) -> Result<()> {
  if retention == 0 {
    return Ok(());
  }
  if let Some(row) = client.query_opt("SELECT app_name FROM runs WHERE id = $1", &[&run_id])? {
    let app_name: String = row.get(0);
    prune_app(client, &app_name, retention)?;
  }
  Ok(())
}

pub(in crate::storage::repository) fn prune_app<C: GenericClient>(
  client: &mut C,
  app_name: &str,
  retention: usize,
) -> Result<()> {
  if retention == 0 {
    return Ok(());
  }
  let offset = i64::try_from(retention).unwrap_or(i64::MAX);
  client.execute(
    "DELETE FROM runs
      WHERE id IN (
        SELECT id FROM runs WHERE app_name = $1 AND status <> 'RUNNING'
        ORDER BY started_at DESC, ended_at DESC NULLS LAST, id DESC OFFSET $2
      )",
    &[&app_name, &offset],
  )?;
  Ok(())
}

fn non_empty(value: &str) -> Option<&str> {
  (!value.is_empty()).then_some(value)
}
