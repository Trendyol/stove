use crate::metrics::{DatabaseOperation, database_result};
use crate::storage::repository::replay::{
  BOUNDS_SQL, ReplayBounds, ReplayPage, ReplayScope, ReplaySize, page_end,
};
use diesel::dsl::max;
use diesel::prelude::*;

use super::SqliteBackend;
use super::writes::apply_persisted_event;
use crate::error::{AppError, Result};
use crate::ingest::{CommitOutcome, EventIdentity, StoredLiveEvent};
use crate::storage::repository::distributed::{
  StoredEventIdentity, duplicate_outcome, live_event_id_to_u64, sequence_to_i64,
};
use crate::storage::schema::sqlite::{dashboard_event_inbox, live_events};

impl SqliteBackend {
  pub fn commit_dashboard_event(
    &self,
    identity: &EventIdentity,
    event: &crate::proto::DashboardEvent,
    retention: usize,
  ) -> Result<CommitOutcome> {
    let mut database = self.lock_write();
    database_result(DatabaseOperation::SqliteIngestTransaction, || {
      database
        .conn()
        .transaction(|conn| commit_on(conn, identity, event, retention))
    })
  }

  pub fn commit_dashboard_batch(
    &self,
    events: &[(EventIdentity, crate::proto::DashboardEvent)],
    retention: usize,
  ) -> Result<Vec<CommitOutcome>> {
    let mut database = self.lock_write();
    database_result(DatabaseOperation::SqliteIngestTransaction, || {
      database.conn().transaction(|conn| {
        events
          .iter()
          .map(|(identity, event)| commit_on(conn, identity, event, retention))
          .collect()
      })
    })
  }

  pub(crate) fn replay_page(
    &self,
    after: u64,
    event_limit: usize,
    byte_limit: usize,
    scope: Option<&ReplayScope>,
  ) -> Result<ReplayPage> {
    let mut database = self.lock_replay();
    database_result(DatabaseOperation::SqliteReplayTransaction, || {
      database.conn().transaction(|conn| {
      let bounds = diesel::sql_query(BOUNDS_SQL).get_result::<ReplayBounds>(conn)?;
      let rows = diesel::sql_query("SELECT id, length(CAST(payload AS BLOB)) AS bytes FROM live_events WHERE id > ?1 AND (NOT ?3 OR event_type IN ('run_started','run_ended','test_started','test_ended') OR (run_id=?4 AND (?5 IS NULL OR json_extract(payload, '$.payload.test_id')=?5))) ORDER BY id LIMIT ?2")
        .bind::<diesel::sql_types::BigInt, _>(i64::try_from(after).unwrap_or(i64::MAX))
        .bind::<diesel::sql_types::BigInt, _>(i64::try_from(event_limit).unwrap_or(i64::MAX))
        .bind::<diesel::sql_types::Bool, _>(scope.is_some())
        .bind::<diesel::sql_types::Nullable<diesel::sql_types::Text>, _>(scope.and_then(|scope| scope.run_id.as_deref()))
        .bind::<diesel::sql_types::Nullable<diesel::sql_types::Text>, _>(scope.and_then(|scope| scope.test_id.as_deref()))
        .load::<ReplaySize>(conn)?;
      let (end, oversized) = page_end(&rows, byte_limit);
      let events = if let Some(end) = end {
        live_events::table.filter(live_events::id.eq_any(rows.iter().filter(|row| row.id <= end).map(|row| row.id))).order(live_events::id)
          .select((live_events::id, live_events::payload)).load::<(i64, String)>(conn)?
          .into_iter().map(|(id, payload)| Ok(StoredLiveEvent { id: live_event_id_to_u64(id)?, json: payload })).collect::<Result<Vec<_>>>()?
      } else { Vec::new() };
      Ok(ReplayPage { events, watermark: live_event_id_to_u64(bounds.watermark)?, deleted_through: live_event_id_to_u64(bounds.deleted_through)?, oversized, exhausted: rows.len() < event_limit && end == rows.last().map(|row| row.id) && oversized.is_none() })
    })
    })
  }

  pub fn latest_live_event_id(&self) -> Result<u64> {
    let mut database = self.lock_replay();
    let bounds = diesel::sql_query(BOUNDS_SQL).get_result::<ReplayBounds>(database.conn())?;
    live_event_id_to_u64(bounds.watermark)
  }

  pub fn live_events_after(&self, after_id: u64, limit: usize) -> Result<Vec<StoredLiveEvent>> {
    let after_id = i64::try_from(after_id).unwrap_or(i64::MAX);
    let limit = i64::try_from(limit).unwrap_or(i64::MAX);
    let mut database = self.lock_replay();
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

fn commit_on(
  conn: &mut SqliteConnection,
  identity: &EventIdentity,
  event: &crate::proto::DashboardEvent,
  retention: usize,
) -> Result<CommitOutcome> {
  let sequence = sequence_to_i64(identity.sequence, "SQLite")?;
  let stored = dashboard_event_inbox::table
    .find(&identity.event_id)
    .select((
      dashboard_event_inbox::run_id,
      dashboard_event_inbox::sequence,
      dashboard_event_inbox::live_event_id,
    ))
    .first::<StoredEventIdentity>(conn)
    .optional()?;
  if let Some(outcome) = duplicate_outcome(identity, &event.run_id, sequence, stored)? {
    return Ok(outcome);
  }

  verify_sequence(conn, &event.run_id, sequence, &identity.event_id)?;
  let event = crate::ingest::EventIngestor::prepare_event(conn, event)?
    .ok_or_else(|| AppError::InvalidEvent("dashboard event has no payload".into()))?;
  let record_id = apply_persisted_event(conn, &event.persisted, retention)?;

  let live_event_id = diesel::insert_into(live_events::table)
    .values((
      live_events::event_id.eq(&identity.event_id),
      live_events::run_id.eq(&event.live.run_id),
      live_events::event_type.eq(&event.live.event_type),
      live_events::payload.eq("{}"),
    ))
    .returning(live_events::id)
    .get_result::<i64>(conn)?;
  let json = serde_json::to_string(
    &event
      .live
      .clone()
      .with_seq(live_event_id_to_u64(live_event_id)?)
      .with_record_id(record_id),
  )?;
  diesel::update(live_events::table.find(live_event_id))
    .set(live_events::payload.eq(json))
    .execute(conn)?;
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
}
