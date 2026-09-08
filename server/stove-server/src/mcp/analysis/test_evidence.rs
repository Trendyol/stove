//! Evidence loaded for one resolved test. Selection and rendering stay with callers.
use super::common::display_error;
use crate::storage::models::{Entry, MockInteraction, MockWarning, Snapshot, Span, Test};
use crate::storage::repository::Repository;

pub(super) struct TestEvidence {
  pub entries: Vec<Entry>,
  pub spans: Vec<Span>,
  pub interactions: Vec<MockInteraction>,
  pub snapshots: Vec<Snapshot>,
  pub warnings: Vec<MockWarning>,
}

impl TestEvidence {
  pub(super) fn load(repository: &Repository, test: &Test) -> Result<Self, String> {
    let run_id = &test.run_id;
    let test_id = &test.id;
    // These reads are not a database snapshot; live-run freshness is reported by callers.
    Ok(Self {
      entries: repository
        .get_entries(run_id, test_id)
        .map_err(display_error)?,
      spans: repository
        .get_spans_for_test(run_id, test_id)
        .map_err(display_error)?,
      interactions: repository
        .get_mock_interactions_for_test(run_id, test_id)
        .map_err(display_error)?,
      snapshots: repository
        .get_snapshots(run_id, test_id)
        .map_err(display_error)?,
      warnings: repository
        .get_mock_warnings_for_test(run_id, test_id)
        .map_err(display_error)?,
    })
  }
}
