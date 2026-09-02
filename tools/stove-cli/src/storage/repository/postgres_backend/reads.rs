use std::collections::BTreeMap;

use crate::error::Result;
use crate::storage::models::{
  AppSummary, Entry, MockInteraction, MockWarning, Run, Snapshot, Span, Test,
};

use super::PostgresBackend;
use super::mapping::{
  app_summary_from_row, entry_from_row, mock_interaction_from_row, mock_warning_from_row,
  run_from_row, snapshot_from_row, span_from_row, test_from_row,
};

const RUN_COLUMNS: &str = "id, app_name, started_at, ended_at, status, total_tests, passed, failed,
   duration_ms, stove_version, systems, metadata::text";

impl PostgresBackend {
  pub fn get_apps(&self) -> Result<Vec<AppSummary>> {
    let mut client = self.lock_read();
    let rows = client.query(
      "SELECT DISTINCT ON (app_name) app_name, id, status, stove_version, metadata::text
       FROM runs
      ORDER BY app_name, started_at DESC, id DESC",
      &[],
    )?;
    Ok(rows.iter().map(app_summary_from_row).collect())
  }

  pub fn get_runs_filtered(
    &self,
    app_name: Option<&str>,
    metadata: &BTreeMap<String, String>,
  ) -> Result<Vec<Run>> {
    let mut client = self.lock_read();
    let metadata_json = serde_json::to_string(metadata)?;
    let rows = match (app_name, metadata.is_empty()) {
      (Some(app_name), true) => client.query(
        &format!(
          "SELECT {RUN_COLUMNS} FROM runs WHERE app_name = $1 ORDER BY started_at DESC, id DESC"
        ),
        &[&app_name],
      )?,
      (Some(app_name), false) => client.query(
        &format!(
          "SELECT {RUN_COLUMNS} FROM runs
          WHERE app_name = $1 AND metadata @> $2::text::jsonb
          ORDER BY started_at DESC, id DESC"
        ),
        &[&app_name, &metadata_json],
      )?,
      (None, true) => client.query(
        &format!("SELECT {RUN_COLUMNS} FROM runs ORDER BY started_at DESC, id DESC"),
        &[],
      )?,
      (None, false) => client.query(
        &format!(
          "SELECT {RUN_COLUMNS} FROM runs
          WHERE metadata @> $1::text::jsonb ORDER BY started_at DESC, id DESC"
        ),
        &[&metadata_json],
      )?,
    };
    Ok(rows.iter().map(run_from_row).collect())
  }

  pub fn get_run(&self, run_id: &str) -> Result<Option<Run>> {
    let mut client = self.lock_read();
    Ok(
      client
        .query_opt(
          &format!("SELECT {RUN_COLUMNS} FROM runs WHERE id = $1"),
          &[&run_id],
        )?
        .as_ref()
        .map(run_from_row),
    )
  }

  pub fn get_tests_for_run(&self, run_id: &str) -> Result<Vec<Test>> {
    let mut client = self.lock_read();
    Ok(
      client
        .query(
          "SELECT id, run_id, test_name, spec_name, test_path, started_at, ended_at, status,
                duration_ms, error FROM tests WHERE run_id = $1 ORDER BY started_at, id",
          &[&run_id],
        )?
        .iter()
        .map(test_from_row)
        .collect(),
    )
  }

  pub fn get_entries(&self, run_id: &str, test_id: &str, raw: bool) -> Result<Vec<Entry>> {
    let mut client = self.lock_read();
    let sql = if raw {
      "SELECT id, run_id, test_id, timestamp, system, action, result, input, output, metadata,
            expected, actual, error, trace_id,
            CASE WHEN assertion_id = '' THEN 'legacy:' || id::text ELSE assertion_id END,
            1::bigint,
            CASE WHEN result IN ('FAILED', 'ERROR') THEN 1::bigint ELSE 0::bigint END
       FROM entries WHERE run_id = $1 AND test_id = $2 ORDER BY timestamp, id"
    } else {
      "WITH correlated AS (
       SELECT id, run_id, test_id, timestamp, system, action, result, input, output, metadata,
              expected, actual, error, trace_id,
              CASE WHEN assertion_id = '' THEN 'legacy:' || id::text ELSE assertion_id END AS assertion_id
         FROM entries WHERE run_id = $1 AND test_id = $2
     ), ranked AS (
       SELECT *, COUNT(*) OVER (PARTITION BY assertion_id) AS attempt_count,
              SUM(CASE WHEN result IN ('FAILED', 'ERROR') THEN 1 ELSE 0 END)
                OVER (PARTITION BY assertion_id) AS failure_count,
              ROW_NUMBER() OVER (PARTITION BY assertion_id ORDER BY id DESC) AS attempt_rank
         FROM correlated
     )
     SELECT id, run_id, test_id, timestamp, system, action, result, input, output, metadata,
            expected, actual, error, trace_id, assertion_id, attempt_count, failure_count
       FROM ranked WHERE attempt_rank = 1 ORDER BY timestamp, id"
    };
    Ok(
      client
        .query(sql, &[&run_id, &test_id])?
        .iter()
        .map(entry_from_row)
        .collect(),
    )
  }

  pub fn get_spans_for_test(&self, run_id: &str, test_id: &str) -> Result<Vec<Span>> {
    let mut client = self.lock_read();
    Ok(
      client
        .query(
          "SELECT id, run_id, trace_id, span_id, parent_span_id, operation_name, service_name,
            start_time_nanos, end_time_nanos, status, attributes, exception_type,
            exception_message, exception_stack_trace
       FROM spans
      WHERE run_id = $1 AND trace_id IN (
        SELECT trace_id FROM entries WHERE run_id = $1 AND test_id = $2 AND trace_id <> ''
        UNION
        SELECT DISTINCT trace_id FROM spans WHERE run_id = $1 AND (
          attributes::jsonb ->> 'x-stove-test-id' = $2 OR
          attributes::jsonb ->> 'X-Stove-Test-Id' = $2 OR
          attributes::jsonb ->> 'stove.test.id' = $2 OR
          attributes::jsonb ->> 'stove_test_id' = $2
        )
      ) ORDER BY start_time_nanos",
          &[&run_id, &test_id],
        )?
        .iter()
        .map(span_from_row)
        .collect(),
    )
  }

  pub fn get_trace(&self, trace_id: &str) -> Result<Vec<Span>> {
    let mut client = self.lock_read();
    Ok(
      client
        .query(
          "SELECT id, run_id, trace_id, span_id, parent_span_id, operation_name, service_name,
            start_time_nanos, end_time_nanos, status, attributes, exception_type,
            exception_message, exception_stack_trace
       FROM spans WHERE trace_id = $1 ORDER BY start_time_nanos",
          &[&trace_id],
        )?
        .iter()
        .map(span_from_row)
        .collect(),
    )
  }

  pub fn get_snapshots(&self, run_id: &str, test_id: &str) -> Result<Vec<Snapshot>> {
    let mut client = self.lock_read();
    Ok(
      client
        .query(
          "SELECT id, run_id, test_id, system, state_json, summary, captured_at, trigger_kind
       FROM snapshots WHERE run_id = $1 AND test_id = $2 ORDER BY id",
          &[&run_id, &test_id],
        )?
        .iter()
        .map(snapshot_from_row)
        .collect(),
    )
  }

  pub fn get_mock_interactions(
    &self,
    run_id: &str,
    test_id: Option<&str>,
    unattributed_only: bool,
  ) -> Result<Vec<MockInteraction>> {
    let mut client = self.lock_read();
    let columns = "id, run_id, test_id, timestamp, system, protocol, method, target, matched,
    stub_id, attribution, request_body, request_body_truncated, response_body,
    response_body_truncated, status, latency_ms, near_misses, trace_id, scenario_name,
    scenario_state, next_scenario_state, configured_delay_ms, fault, client_deadline_ms";
    let rows = match test_id {
    Some(test_id) => client.query(
      &format!(
        "SELECT {columns} FROM mock_interactions WHERE run_id = $1 AND test_id = $2 ORDER BY id"
      ),
      &[&run_id, &test_id],
    )?,
    None if unattributed_only => client.query(
      &format!(
        "SELECT {columns} FROM mock_interactions WHERE run_id = $1 AND test_id IS NULL ORDER BY id"
      ),
      &[&run_id],
    )?,
    None => client.query(
      &format!("SELECT {columns} FROM mock_interactions WHERE run_id = $1 ORDER BY id"),
      &[&run_id],
    )?,
  };
    Ok(rows.iter().map(mock_interaction_from_row).collect())
  }

  pub fn get_mock_warnings(
    &self,
    run_id: &str,
    test_id: Option<&str>,
    unattributed_only: bool,
  ) -> Result<Vec<MockWarning>> {
    let mut client = self.lock_read();
    let columns = "id, run_id, test_id, timestamp, system, kind, message, stub_id, target";
    let rows = match test_id {
      Some(test_id) => client.query(
        &format!(
          "SELECT {columns} FROM mock_warnings WHERE run_id = $1 AND test_id = $2 ORDER BY id"
        ),
        &[&run_id, &test_id],
      )?,
      None if unattributed_only => client.query(
        &format!(
          "SELECT {columns} FROM mock_warnings WHERE run_id = $1 AND test_id IS NULL ORDER BY id"
        ),
        &[&run_id],
      )?,
      None => client.query(
        &format!("SELECT {columns} FROM mock_warnings WHERE run_id = $1 ORDER BY id"),
        &[&run_id],
      )?,
    };
    Ok(rows.iter().map(mock_warning_from_row).collect())
  }
}
