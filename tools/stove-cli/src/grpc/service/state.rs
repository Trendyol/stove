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
  assertion_attempts: HashMap<(String, String, String), AssertionSequence>,
}

impl LiveState {
  pub(super) fn record_assertion_attempt(
    &mut self,
    run_id: &str,
    test_id: &str,
    correlation_id: &str,
    new_assertion_id: &str,
    failed: bool,
  ) -> Option<(String, AssertionAttempts)> {
    let key = (
      run_id.to_string(),
      test_id.to_string(),
      correlation_id.to_string(),
    );
    if failed {
      let attempts = self
        .assertion_attempts
        .entry(key)
        .or_insert_with(|| AssertionSequence::new(new_assertion_id));
      attempts.counts.attempt_count += 1;
      attempts.counts.failure_count += 1;
      return Some((attempts.assertion_id.clone(), attempts.counts));
    }

    self.assertion_attempts.remove(&key).map(|mut attempts| {
      attempts.counts.attempt_count += 1;
      (attempts.assertion_id, attempts.counts)
    })
  }

  pub(super) fn clear_run(&mut self, run_id: &str) {
    self.runs.remove(run_id);
    self
      .tests
      .retain(|(known_run_id, _)| known_run_id != run_id);
    self
      .traces
      .retain(|(known_run_id, _), _| known_run_id != run_id);
    self
      .assertion_attempts
      .retain(|(known_run_id, _, _), _| known_run_id != run_id);
  }

  pub(super) fn end_test(&mut self, run_id: &str, test_id: &str) {
    self
      .tests
      .remove(&(run_id.to_string(), test_id.to_string()));
    self
      .assertion_attempts
      .retain(|(known_run_id, known_test_id, _), _| {
        known_run_id != run_id || known_test_id != test_id
      });
  }
}

struct AssertionSequence {
  assertion_id: String,
  counts: AssertionAttempts,
}

impl AssertionSequence {
  fn new(assertion_id: &str) -> Self {
    Self {
      assertion_id: assertion_id.to_string(),
      counts: AssertionAttempts::default(),
    }
  }
}

#[derive(Clone, Copy, Default)]
pub(super) struct AssertionAttempts {
  pub(super) attempt_count: i64,
  pub(super) failure_count: i64,
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

#[cfg(test)]
mod tests {
  use super::LiveState;

  #[test]
  fn retry_sequences_share_an_id_until_the_first_success() {
    let mut state = LiveState::default();

    assert!(
      state
        .record_assertion_attempt("run", "test", "signature", "pass-1", false)
        .is_none(),
      "standalone successes must remain distinct dashboard entries"
    );

    let (first_id, first) = state
      .record_assertion_attempt("run", "test", "signature", "retry-1", true)
      .unwrap();
    let (second_id, second) = state
      .record_assertion_attempt("run", "test", "signature", "ignored", true)
      .unwrap();
    let (final_id, final_attempt) = state
      .record_assertion_attempt("run", "test", "signature", "ignored", false)
      .unwrap();

    assert_eq!(first_id, "retry-1");
    assert_eq!(second_id, first_id);
    assert_eq!(final_id, first_id);
    assert_eq!(first.attempt_count, 1);
    assert_eq!(second.attempt_count, 2);
    assert_eq!(final_attempt.attempt_count, 3);
    assert_eq!(final_attempt.failure_count, 2);

    let (next_id, next) = state
      .record_assertion_attempt("run", "test", "signature", "retry-2", true)
      .unwrap();
    assert_eq!(next_id, "retry-2");
    assert_eq!(next.attempt_count, 1);
  }
}
