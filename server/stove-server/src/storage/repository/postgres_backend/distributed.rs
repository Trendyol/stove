use crate::metrics::{DatabaseOperation, database_result};
use std::time::Duration;

use crate::storage::repository::replay::{
  BOUNDS_SQL, ReplayBounds, ReplayPage, ReplayScope, ReplaySize, page_end,
};
use diesel::dsl::max;
use diesel::prelude::*;
use diesel::sql_types::Text;
use fallible_iterator::FallibleIterator;
use tokio::sync::mpsc;

use super::PostgresBackend;
use super::writes::{apply_event, retention_on};
use crate::error::{AppError, Result};
use crate::ingest::{CommitOutcome, EventIdentity, StoredLiveEvent};
use crate::storage::repository::distributed::{
  StoredEventIdentity, duplicate_outcome, live_event_id_to_u64, sequence_to_i64,
};
use crate::storage::schema::postgres::{dashboard_event_inbox, live_events};

// PostgreSQL sequences allocate before commit, so two concurrent transactions can
// otherwise commit IDs out of order and make a cursor skip the lower ID forever.
// This two-key advisory lock uses a namespace separate from the per-run bigint lock
// and serializes only the small outbox-insertion tail of each transaction.
const LIVE_EVENT_ORDER_LOCK: &str = "SELECT pg_advisory_xact_lock(1937012086, 1)";

impl PostgresBackend {
  pub fn subscribe_live_event_notifications(&self) -> mpsc::Receiver<()> {
    let (sender, receiver) = mpsc::channel(1);
    let database_url = self.database_url().to_string();
    let listener = std::thread::Builder::new()
      .name("stove-postgres-live-events".to_string())
      .spawn(move || {
        if let Err(error) = listen_for_live_events(&database_url, &sender) {
          tracing::warn!(%error, "PostgreSQL live-event listener stopped; polling remains active");
        }
      });
    if let Err(error) = listener {
      tracing::warn!(%error, "Could not spawn PostgreSQL live-event listener; polling remains active");
    }
    receiver
  }

  pub fn commit_dashboard_event(
    &self,
    identity: &EventIdentity,
    event: &crate::proto::DashboardEvent,
  ) -> Result<CommitOutcome> {
    let mut database = self.lock_write();
    database_result(DatabaseOperation::PostgresIngestTransaction, || {
      database.transaction(|conn| commit_on(conn, identity, event))
    })
  }

  pub fn commit_dashboard_batch(
    &self,
    events: &[(EventIdentity, crate::proto::DashboardEvent)],
  ) -> Result<Vec<CommitOutcome>> {
    let mut database = self.lock_write();
    database_result(DatabaseOperation::PostgresIngestTransaction, || {
      database.transaction(|conn| {
        events
          .iter()
          .map(|(identity, event)| commit_on(conn, identity, event))
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
    database_result(DatabaseOperation::PostgresReplayTransaction, || {
      database.build_transaction().repeatable_read().read_only().run(|conn| {
      let bounds = diesel::sql_query(BOUNDS_SQL).get_result::<ReplayBounds>(conn)?;
      let rows = diesel::sql_query("SELECT id, octet_length(payload::text)::bigint AS bytes FROM live_events WHERE id > $1 AND (NOT $3 OR event_type IN ('run_started','run_ended','test_started','test_ended') OR (run_id=$4 AND ($5 IS NULL OR payload->'payload'->>'test_id'=$5))) ORDER BY id LIMIT $2")
        .bind::<diesel::sql_types::BigInt, _>(i64::try_from(after).unwrap_or(i64::MAX))
        .bind::<diesel::sql_types::BigInt, _>(i64::try_from(event_limit).unwrap_or(i64::MAX))
        .bind::<diesel::sql_types::Bool, _>(scope.is_some())
        .bind::<diesel::sql_types::Nullable<diesel::sql_types::Text>, _>(scope.and_then(|scope| scope.run_id.as_deref()))
        .bind::<diesel::sql_types::Nullable<diesel::sql_types::Text>, _>(scope.and_then(|scope| scope.test_id.as_deref()))
        .load::<ReplaySize>(conn)?;
      let (end, oversized) = page_end(&rows, byte_limit);
      let events = if let Some(end) = end {
        live_events::table.filter(live_events::id.eq_any(rows.iter().filter(|row| row.id <= end).map(|row| row.id))).order(live_events::id)
          .select((live_events::id, live_events::payload)).load::<(i64, serde_json::Value)>(conn)?
          .into_iter().map(|(id, payload)| Ok(StoredLiveEvent { id: live_event_id_to_u64(id)?, json: serde_json::to_string(&payload)? })).collect::<Result<Vec<_>>>()?
      } else { Vec::new() };
      Ok(ReplayPage { events, watermark: live_event_id_to_u64(bounds.watermark)?, deleted_through: live_event_id_to_u64(bounds.deleted_through)?, oversized, exhausted: rows.len() < event_limit && end == rows.last().map(|row| row.id) && oversized.is_none() })
    })
    })
  }

  pub fn latest_live_event_id(&self) -> Result<u64> {
    let mut database = self.lock_replay();
    let bounds = diesel::sql_query(BOUNDS_SQL).get_result::<ReplayBounds>(&mut *database)?;
    live_event_id_to_u64(bounds.watermark)
  }

  pub fn live_events_after(&self, after_id: u64, limit: usize) -> Result<Vec<StoredLiveEvent>> {
    let after_id = i64::try_from(after_id).unwrap_or(i64::MAX);
    let limit = i64::try_from(limit).unwrap_or(i64::MAX);
    let mut conn = self.lock_replay();
    live_events::table
      .filter(live_events::id.gt(after_id))
      .order(live_events::id)
      .limit(limit)
      .select((live_events::id, live_events::payload))
      .load::<(i64, serde_json::Value)>(&mut *conn)?
      .into_iter()
      .map(|(id, payload)| {
        Ok(StoredLiveEvent {
          id: live_event_id_to_u64(id)?,
          json: serde_json::to_string(&payload)?,
        })
      })
      .collect()
  }
}

fn listen_for_live_events(database_url: &str, sender: &mpsc::Sender<()>) -> Result<()> {
  let mut client = super::database::connect_driver(database_url)?;
  client.batch_execute("LISTEN stove_live_events")?;
  let mut notifications = client.notifications();
  while !sender.is_closed() {
    if notifications
      .timeout_iter(Duration::from_secs(1))
      .next()?
      .is_some()
      && matches!(
        sender.try_send(()),
        Err(mpsc::error::TrySendError::Closed(()))
      )
    {
      break;
    }
  }
  Ok(())
}

fn verify_sequence(
  conn: &mut PgConnection,
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
  conn: &mut PgConnection,
  identity: &EventIdentity,
  event: &crate::proto::DashboardEvent,
) -> Result<CommitOutcome> {
  let sequence = sequence_to_i64(identity.sequence, "PostgreSQL")?;
  diesel::sql_query("SELECT pg_advisory_xact_lock(hashtextextended($1, 0))")
    .bind::<Text, _>(&event.run_id)
    .execute(conn)?;

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
  let retention = retention_on(conn)?;
  let record_id = apply_event(conn, &event.persisted, retention)?;

  diesel::sql_query(LIVE_EVENT_ORDER_LOCK).execute(conn)?;
  let live_event_id = diesel::insert_into(live_events::table)
    .values((
      live_events::event_id.eq(&identity.event_id),
      live_events::run_id.eq(&event.live.run_id),
      live_events::event_type.eq(&event.live.event_type),
      live_events::payload.eq(serde_json::json!({})),
    ))
    .returning(live_events::id)
    .get_result::<i64>(conn)?;
  let payload = serde_json::to_value(
    event
      .live
      .clone()
      .with_seq(live_event_id_to_u64(live_event_id)?)
      .with_record_id(record_id),
  )?;
  diesel::update(live_events::table.find(live_event_id))
    .set(live_events::payload.eq(payload))
    .execute(conn)?;
  diesel::insert_into(dashboard_event_inbox::table)
    .values((
      dashboard_event_inbox::event_id.eq(&identity.event_id),
      dashboard_event_inbox::run_id.eq(&event.live.run_id),
      dashboard_event_inbox::sequence.eq(sequence),
      dashboard_event_inbox::live_event_id.eq(live_event_id),
    ))
    .execute(conn)?;
  diesel::sql_query("SELECT pg_notify('stove_live_events', $1)")
    .bind::<Text, _>(live_event_id.to_string())
    .execute(conn)?;
  Ok(CommitOutcome {
    duplicate: false,
    live_event_id: live_event_id_to_u64(live_event_id)?,
  })
}
