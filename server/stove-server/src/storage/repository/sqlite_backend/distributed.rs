use diesel::dsl::max;
use diesel::prelude::*;

use super::SqliteBackend;
use super::writes::apply_persisted_event;
use crate::error::{AppError, Result};
use crate::ingest::{CommitOutcome, EventIdentity, PreparedDashboardEvent, StoredLiveEvent};
use crate::storage::repository::distributed::{
  StoredEventIdentity, duplicate_outcome, live_event_id_to_u64, sequence_to_i64,
};
use crate::storage::schema::sqlite::{dashboard_event_inbox, live_events};

impl SqliteBackend {
  pub fn commit_dashboard_event(
    &self,
    identity: &EventIdentity,
    event: &PreparedDashboardEvent,
    retention: usize,
  ) -> Result<CommitOutcome> {
    let sequence = sequence_to_i64(identity.sequence, "SQLite")?;
    let mut database = self.lock_write();
    database.conn().transaction(|conn| {
      let stored = dashboard_event_inbox::table
        .find(&identity.event_id)
        .select((
          dashboard_event_inbox::run_id,
          dashboard_event_inbox::sequence,
          dashboard_event_inbox::live_event_id,
        ))
        .first::<StoredEventIdentity>(conn)
        .optional()?;
      if let Some(outcome) = duplicate_outcome(identity, &event.live.run_id, sequence, stored)? {
        return Ok(outcome);
      }

      verify_sequence(conn, &event.live.run_id, sequence, &identity.event_id)?;
      apply_persisted_event(conn, &event.persisted, retention)?;
      super::lifecycle::touch_run(conn, &event.live.run_id)?;

      let live_event_id = append_live_event(conn, &identity.event_id, &event.live)?;
      diesel::insert_into(dashboard_event_inbox::table)
        .values((
          dashboard_event_inbox::event_id.eq(&identity.event_id),
          dashboard_event_inbox::run_id.eq(&event.live.run_id),
          dashboard_event_inbox::sequence.eq(sequence),
          dashboard_event_inbox::live_event_id.eq(live_event_id),
        ))
        .execute(conn)?;
      Ok(CommitOutcome {
        duplicate: false,
        live_event_id: live_event_id_to_u64(live_event_id)?,
      })
    })
  }

  pub fn latest_live_event_id(&self) -> Result<u64> {
    let mut database = self.lock_read();
    let id = live_events::table
      .select(max(live_events::id))
      .first::<Option<i64>>(database.conn())?
      .unwrap_or_default();
    live_event_id_to_u64(id)
  }

  pub fn live_events_after(&self, after_id: u64, limit: usize) -> Result<Vec<StoredLiveEvent>> {
    let after_id = i64::try_from(after_id).unwrap_or(i64::MAX);
    let limit = i64::try_from(limit).unwrap_or(i64::MAX);
    let mut database = self.lock_read();
    live_events::table
      .filter(live_events::id.gt(after_id))
      .order(live_events::id)
      .limit(limit)
      .select((live_events::id, live_events::payload))
      .load::<(i64, String)>(database.conn())?
      .into_iter()
      .map(|(id, json)| {
        Ok(StoredLiveEvent {
          id: live_event_id_to_u64(id)?,
          json,
        })
      })
      .collect()
  }
}

fn verify_sequence(
  conn: &mut SqliteConnection,
  run_id: &str,
  sequence: Option<i64>,
  event_id: &str,
) -> Result<()> {
  let Some(sequence) = sequence else {
    return Ok(());
  };
  if let Some(existing_event_id) = dashboard_event_inbox::table
    .filter(dashboard_event_inbox::run_id.eq(run_id))
    .filter(dashboard_event_inbox::sequence.eq(sequence))
    .select(dashboard_event_inbox::event_id)
    .first::<String>(conn)
    .optional()?
  {
    return Err(AppError::InvalidEvent(format!(
      "sequence {sequence} for run `{run_id}` belongs to event `{existing_event_id}`, not `{event_id}`"
    )));
  }
  let previous = dashboard_event_inbox::table
    .filter(dashboard_event_inbox::run_id.eq(run_id))
    .select(max(dashboard_event_inbox::sequence))
    .first::<Option<i64>>(conn)?
    .unwrap_or_default();
  if sequence != previous + 1 {
    return Err(AppError::InvalidEvent(format!(
      "expected sequence {} for run `{run_id}`, received {sequence}",
      previous + 1
    )));
  }
  Ok(())
}

pub(super) fn append_live_event(
  conn: &mut SqliteConnection,
  event_id: &str,
  live: &crate::ingest::LiveDashboardEvent,
) -> Result<i64> {
  let mut live = live.clone();
  if let crate::ingest::LiveDashboardPayload::RunStarted(payload) = &mut live.payload {
    use crate::storage::schema::sqlite::runs;
    payload.last_activity_at = runs::table
      .find(&live.run_id)
      .select(runs::last_activity_at)
      .first(conn)?;
  }

  let live_event_id = diesel::insert_into(live_events::table)
    .values((
      live_events::event_id.eq(event_id),
      live_events::run_id.eq(live.parent_run_id()),
      live_events::event_type.eq(live.event_type()),
      live_events::payload.eq("{}"),
    ))
    .returning(live_events::id)
    .get_result::<i64>(conn)?;
  let json = serde_json::to_string(&live.with_seq(live_event_id_to_u64(live_event_id)?))?;
  diesel::update(live_events::table.find(live_event_id))
    .set(live_events::payload.eq(json))
    .execute(conn)?;
  Ok(live_event_id)
}
