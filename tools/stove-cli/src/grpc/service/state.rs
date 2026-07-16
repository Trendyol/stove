//! Live-event ordering state for the dashboard gRPC service.
//!
//! Tracks which runs/tests have been observed so the service can reject
//! out-of-order events (e.g. an `EntryRecorded` for a test that never started)
//! before they pollute the SSE stream or the persistence batch.

use std::collections::HashMap;
use std::collections::HashSet;

use crate::error::AppError;
use crate::error::Result as AppResult;

pub(super) mod event_type {
  pub const RUN_STARTED: &str = "run_started";
  pub const RUN_ENDED: &str = "run_ended";
  pub const TEST_STARTED: &str = "test_started";
  pub const TEST_ENDED: &str = "test_ended";
  pub const ENTRY_RECORDED: &str = "entry_recorded";
  pub const SPAN_RECORDED: &str = "span_recorded";
  pub const SNAPSHOT: &str = "snapshot";
  pub const MOCK_INTERACTION: &str = "mock_interaction";
  pub const MOCK_WARNING: &str = "mock_warning";
}

#[derive(Default)]
pub(super) struct LiveState {
  pub(super) runs: HashSet<String>,
  pub(super) tests: HashSet<(String, String)>,
  pub(super) traces: HashMap<(String, String), String>,
}

impl LiveState {
  pub(super) fn clear_run(&mut self, run_id: &str) {
    self.runs.remove(run_id);
    self
      .tests
      .retain(|(known_run_id, _)| known_run_id != run_id);
    self
      .traces
      .retain(|(known_run_id, _), _| known_run_id != run_id);
  }
}

pub(super) fn ensure_run_known(state: &LiveState, run_id: &str) -> AppResult<()> {
  if state.runs.contains(run_id) {
    Ok(())
  } else {
    Err(AppError::InvalidEvent(format!(
      "received event for unknown run `{run_id}`"
    )))
  }
}

pub(super) fn ensure_test_known(state: &LiveState, run_id: &str, test_id: &str) -> AppResult<()> {
  ensure_run_known(state, run_id)?;
  if state
    .tests
    .contains(&(run_id.to_string(), test_id.to_string()))
  {
    Ok(())
  } else {
    Err(AppError::InvalidEvent(format!(
      "received event for unknown test `{test_id}` in run `{run_id}`"
    )))
  }
}
