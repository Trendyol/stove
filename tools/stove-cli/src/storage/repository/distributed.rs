use super::{Backend, Repository, run_blocking};
use crate::error::{AppError, Result};
use crate::ingest::{CommitOutcome, EventIdentity, PreparedDashboardEvent, StoredLiveEvent};
use tokio::sync::mpsc;

pub(super) type StoredEventIdentity = (String, Option<i64>, i64);

pub(super) fn sequence_to_i64(sequence: Option<u64>, backend: &str) -> Result<Option<i64>> {
  sequence
    .map(i64::try_from)
    .transpose()
    .map_err(|_| AppError::InvalidEvent(format!("event sequence exceeds {backend} range")))
}

pub(super) fn live_event_id_to_u64(value: i64) -> Result<u64> {
  u64::try_from(value)
    .map_err(|_| AppError::Startup(format!("invalid negative live event id {value}")))
}

pub(super) fn duplicate_outcome(
  identity: &EventIdentity,
  run_id: &str,
  sequence: Option<i64>,
  stored: Option<StoredEventIdentity>,
) -> Result<Option<CommitOutcome>> {
  let Some((stored_run_id, stored_sequence, live_event_id)) = stored else {
    return Ok(None);
  };
  if stored_run_id != run_id || stored_sequence != sequence {
    return Err(AppError::InvalidEvent(format!(
      "event id `{}` was already used with different ordering metadata",
      identity.event_id
    )));
  }
  Ok(Some(CommitOutcome {
    duplicate: true,
    live_event_id: live_event_id_to_u64(live_event_id)?,
  }))
}

impl Repository {
  pub fn subscribe_live_event_notifications(&self) -> mpsc::UnboundedReceiver<()> {
    match &self.backend {
      Backend::Sqlite(_) => {
        let (_sender, receiver) = mpsc::unbounded_channel();
        receiver
      }
      Backend::Postgres(postgres) => postgres.subscribe_live_event_notifications(),
    }
  }

  pub(crate) fn commit_dashboard_event(
    &self,
    identity: &EventIdentity,
    event: &PreparedDashboardEvent,
  ) -> Result<CommitOutcome> {
    match &self.backend {
      Backend::Sqlite(sqlite) => {
        sqlite.commit_dashboard_event(identity, event, self.retention_runs_per_app())
      }
      Backend::Postgres(postgres) => {
        run_blocking(|| postgres.commit_dashboard_event(identity, event))
      }
    }
  }

  pub fn latest_live_event_id(&self) -> Result<u64> {
    match &self.backend {
      Backend::Sqlite(sqlite) => sqlite.latest_live_event_id(),
      Backend::Postgres(postgres) => run_blocking(|| postgres.latest_live_event_id()),
    }
  }

  pub fn live_events_after(&self, after_id: u64, limit: usize) -> Result<Vec<StoredLiveEvent>> {
    match &self.backend {
      Backend::Sqlite(sqlite) => sqlite.live_events_after(after_id, limit),
      Backend::Postgres(postgres) => run_blocking(|| postgres.live_events_after(after_id, limit)),
    }
  }
}
