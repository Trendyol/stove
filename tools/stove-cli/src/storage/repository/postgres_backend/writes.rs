//! Diesel-backed `PostgreSQL` writes. Retention remains a set-based operation;
//! all ordinary inserts and updates use Diesel's query builder.

use diesel::prelude::*;

use super::PostgresBackend;
use crate::error::{AppError, Result};
use crate::ingest::PersistedDashboardEvent;
use crate::storage::models::{NewEntry, NewMockInteraction, NewMockWarning, NewSpan};
use crate::storage::repository::write_models::{
  RunEnd, RunStart, SnapshotWrite, TestEnd, TestStart, WriteOperation, non_empty,
};
use crate::storage::schema::postgres::{
  dashboard_settings, entries, mock_interactions, mock_warnings, runs, snapshots, spans, tests,
};

impl PostgresBackend {
  pub fn save_run_start(&self, run: &RunStart<'_>) -> Result<()> {
    self
      .lock_write()
      .transaction(|conn| save_run_start_on(conn, run))
  }

  pub fn save_run_end(&self, run: &RunEnd<'_>) -> Result<()> {
    self.lock_write().transaction(|conn| {
      let retention = retention_on(conn)?;
      save_run_end_on(conn, run)?;
      prune_for_completed_run(conn, run.run_id, retention)
    })
  }

  pub fn save_test_start(&self, test: &TestStart<'_>) -> Result<()> {
    save_test_start_on(&mut self.lock_write(), test)
  }

  pub fn save_test_end(&self, test: &TestEnd<'_>) -> Result<()> {
    save_test_end_on(&mut self.lock_write(), test)
  }

  pub fn save_entry(&self, entry: &NewEntry) -> Result<()> {
    save_entry_on(&mut self.lock_write(), entry)
  }

  pub fn save_span(&self, span: &NewSpan) -> Result<()> {
    save_span_on(&mut self.lock_write(), span)
  }

  pub fn save_snapshot(&self, snapshot: &SnapshotWrite<'_>) -> Result<()> {
    save_snapshot_on(&mut self.lock_write(), snapshot)
  }

  pub fn save_mock_interaction(&self, interaction: &NewMockInteraction) -> Result<()> {
    save_mock_interaction_on(&mut self.lock_write(), interaction)
  }

  pub fn save_mock_warning(&self, warning: &NewMockWarning) -> Result<()> {
    save_mock_warning_on(&mut self.lock_write(), warning)
  }

  pub fn clear_all(&self) -> Result<()> {
    let mut conn = self.lock_write();
    diesel::delete(runs::table).execute(&mut *conn)?;
    Ok(())
  }
}

pub(super) fn apply_event(
  conn: &mut PgConnection,
  event: &PersistedDashboardEvent,
  retention: usize,
) -> Result<()> {
  match WriteOperation::from(event) {
    WriteOperation::RunStarted(run) => save_run_start_on(conn, &run),
    WriteOperation::RunEnded(run) => {
      save_run_end_on(conn, &run)?;
      prune_for_completed_run(conn, run.run_id, retention)
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

fn save_run_start_on(conn: &mut PgConnection, run: &RunStart<'_>) -> Result<()> {
  let systems = serde_json::to_string(run.systems)?;
  let metadata = serde_json::to_value(run.metadata)?;
  diesel::insert_into(runs::table)
    .values((
      runs::id.eq(run.run_id),
      runs::app_name.eq(run.app_name),
      runs::started_at.eq(run.started_at),
      runs::stove_version.eq(run.stove_version),
      runs::systems.eq(&systems),
      runs::metadata.eq(&metadata),
    ))
    .on_conflict(runs::id)
    .do_update()
    .set((
      runs::app_name.eq(run.app_name),
      runs::started_at.eq(run.started_at),
      runs::stove_version.eq(run.stove_version),
      runs::systems.eq(&systems),
      runs::metadata.eq(&metadata),
    ))
    .execute(conn)?;
  Ok(())
}

fn save_run_end_on(conn: &mut PgConnection, run: &RunEnd<'_>) -> Result<()> {
  diesel::update(runs::table.find(run.run_id))
    .set((
      runs::ended_at.eq(run.ended_at),
      runs::status.eq(run.status().to_string()),
      runs::total_tests.eq(run.total_tests),
      runs::passed.eq(run.passed),
      runs::failed.eq(run.failed),
      runs::duration_ms.eq(run.duration_ms),
    ))
    .execute(conn)?;
  Ok(())
}

fn save_test_start_on(conn: &mut PgConnection, test: &TestStart<'_>) -> Result<()> {
  let test_path = serde_json::to_string(test.test_path)?;
  diesel::insert_into(tests::table)
    .values((
      tests::id.eq(test.test_id),
      tests::run_id.eq(test.run_id),
      tests::test_name.eq(test.test_name),
      tests::spec_name.eq(test.spec_name),
      tests::test_path.eq(&test_path),
      tests::started_at.eq(test.started_at),
    ))
    .on_conflict((tests::run_id, tests::id))
    .do_update()
    .set((
      tests::test_name.eq(test.test_name),
      tests::spec_name.eq(test.spec_name),
      tests::test_path.eq(&test_path),
      tests::started_at.eq(test.started_at),
    ))
    .execute(conn)?;
  Ok(())
}

fn save_test_end_on(conn: &mut PgConnection, test: &TestEnd<'_>) -> Result<()> {
  diesel::update(
    tests::table
      .filter(tests::run_id.eq(test.run_id))
      .filter(tests::id.eq(test.test_id)),
  )
  .set((
    tests::ended_at.eq(test.ended_at),
    tests::status.eq(test.status),
    tests::duration_ms.eq(test.duration_ms),
    tests::error.eq(non_empty(test.error)),
  ))
  .execute(conn)?;
  Ok(())
}

fn save_entry_on(conn: &mut PgConnection, entry: &NewEntry) -> Result<()> {
  diesel::insert_into(entries::table)
    .values((
      entries::run_id.eq(&entry.run_id),
      entries::test_id.eq(&entry.test_id),
      entries::timestamp.eq(&entry.timestamp),
      entries::system.eq(&entry.system),
      entries::action.eq(&entry.action),
      entries::result.eq(&entry.result),
      entries::input.eq(non_empty(&entry.input)),
      entries::output.eq(non_empty(&entry.output)),
      entries::metadata.eq(non_empty(&entry.metadata)),
      entries::expected.eq(non_empty(&entry.expected)),
      entries::actual.eq(non_empty(&entry.actual)),
      entries::error.eq(non_empty(&entry.error)),
      entries::trace_id.eq(non_empty(&entry.trace_id)),
      entries::assertion_id.eq(&entry.assertion_id),
      entries::correlation_key.eq(&entry.correlation_key),
    ))
    .execute(conn)?;
  Ok(())
}

fn save_span_on(conn: &mut PgConnection, span: &NewSpan) -> Result<()> {
  diesel::insert_into(spans::table)
    .values((
      spans::run_id.eq(&span.run_id),
      spans::trace_id.eq(&span.trace_id),
      spans::span_id.eq(&span.span_id),
      spans::parent_span_id.eq(non_empty(&span.parent_span_id)),
      spans::operation_name.eq(&span.operation_name),
      spans::service_name.eq(&span.service_name),
      spans::start_time_nanos.eq(span.start_time_nanos),
      spans::end_time_nanos.eq(span.end_time_nanos),
      spans::status.eq(&span.status),
      spans::attributes.eq(non_empty(&span.attributes)),
      spans::exception_type.eq(non_empty(&span.exception_type)),
      spans::exception_message.eq(non_empty(&span.exception_message)),
      spans::exception_stack_trace.eq(non_empty(&span.exception_stack_trace)),
    ))
    .execute(conn)?;
  Ok(())
}

fn save_snapshot_on(conn: &mut PgConnection, snapshot: &SnapshotWrite<'_>) -> Result<()> {
  diesel::insert_into(snapshots::table)
    .values((
      snapshots::run_id.eq(snapshot.run_id),
      snapshots::test_id.eq(snapshot.test_id),
      snapshots::system.eq(snapshot.system),
      snapshots::state_json.eq(snapshot.state_json),
      snapshots::summary.eq(snapshot.summary),
      snapshots::captured_at.eq(non_empty(snapshot.captured_at)),
      snapshots::trigger_kind.eq(snapshot.trigger),
    ))
    .execute(conn)?;
  Ok(())
}

fn save_mock_interaction_on(
  conn: &mut PgConnection,
  interaction: &NewMockInteraction,
) -> Result<()> {
  diesel::insert_into(mock_interactions::table)
    .values((
      mock_interactions::run_id.eq(&interaction.run_id),
      mock_interactions::test_id.eq(&interaction.test_id),
      mock_interactions::timestamp.eq(&interaction.timestamp),
      mock_interactions::system.eq(&interaction.system),
      mock_interactions::protocol.eq(&interaction.protocol),
      mock_interactions::method.eq(&interaction.method),
      mock_interactions::target.eq(&interaction.target),
      mock_interactions::matched.eq(interaction.matched),
      mock_interactions::stub_id.eq(&interaction.stub_id),
      mock_interactions::attribution.eq(&interaction.attribution),
      mock_interactions::request_body.eq(non_empty(&interaction.request_body)),
      mock_interactions::request_body_truncated.eq(interaction.request_body_truncated),
      mock_interactions::response_body.eq(non_empty(&interaction.response_body)),
      mock_interactions::response_body_truncated.eq(interaction.response_body_truncated),
      mock_interactions::status.eq(&interaction.status),
      mock_interactions::latency_ms.eq(interaction.latency_ms),
      mock_interactions::near_misses.eq(non_empty(&interaction.near_misses)),
      mock_interactions::trace_id.eq(&interaction.trace_id),
      mock_interactions::scenario_name.eq(&interaction.scenario_name),
      mock_interactions::scenario_state.eq(&interaction.scenario_state),
      mock_interactions::next_scenario_state.eq(&interaction.next_scenario_state),
      mock_interactions::configured_delay_ms.eq(interaction.configured_delay_ms),
      mock_interactions::fault.eq(&interaction.fault),
      mock_interactions::client_deadline_ms.eq(interaction.client_deadline_ms),
    ))
    .execute(conn)?;
  Ok(())
}

fn save_mock_warning_on(conn: &mut PgConnection, warning: &NewMockWarning) -> Result<()> {
  diesel::insert_into(mock_warnings::table)
    .values((
      mock_warnings::run_id.eq(&warning.run_id),
      mock_warnings::test_id.eq(&warning.test_id),
      mock_warnings::timestamp.eq(&warning.timestamp),
      mock_warnings::system.eq(&warning.system),
      mock_warnings::kind.eq(&warning.kind),
      mock_warnings::message.eq(&warning.message),
      mock_warnings::stub_id.eq(&warning.stub_id),
      mock_warnings::target.eq(&warning.target),
    ))
    .execute(conn)?;
  Ok(())
}

fn prune_for_completed_run(conn: &mut PgConnection, run_id: &str, retention: usize) -> Result<()> {
  if retention == 0 {
    return Ok(());
  }
  if let Some(app_name) = runs::table
    .find(run_id)
    .select(runs::app_name)
    .first::<String>(conn)
    .optional()?
  {
    prune_app(conn, &app_name, retention)?;
  }
  Ok(())
}

pub(in crate::storage::repository) fn prune_app(
  conn: &mut PgConnection,
  app_name: &str,
  retention: usize,
) -> Result<()> {
  if retention == 0 {
    return Ok(());
  }
  let offset = i64::try_from(retention).unwrap_or(i64::MAX);
  let expired = runs::table
    .filter(runs::app_name.eq(app_name))
    .filter(runs::status.ne("RUNNING"))
    .order((
      runs::started_at.desc(),
      runs::ended_at.desc(),
      runs::id.desc(),
    ))
    .offset(offset)
    .select(runs::id)
    .load::<String>(conn)?;
  diesel::delete(runs::table.filter(runs::id.eq_any(expired))).execute(conn)?;
  Ok(())
}

pub(in crate::storage::repository) fn retention_on(conn: &mut PgConnection) -> Result<usize> {
  let value = dashboard_settings::table
    .find("retention_runs_per_app")
    .select(dashboard_settings::setting_value)
    .first::<String>(conn)?;
  value.parse().map_err(|error| {
    AppError::Startup(format!(
      "invalid PostgreSQL retention setting `{value}`: {error}"
    ))
  })
}
