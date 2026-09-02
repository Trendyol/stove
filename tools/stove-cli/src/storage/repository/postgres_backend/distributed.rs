use std::time::Duration;

use diesel::dsl::max;
use diesel::prelude::*;
use diesel::sql_types::Text;
use fallible_iterator::FallibleIterator;
use tokio::sync::mpsc;

use super::PostgresBackend;
use super::writes::{apply_event, retention_on};
use crate::error::{AppError, Result};
use crate::ingest::{CommitOutcome, EventIdentity, PreparedDashboardEvent, StoredLiveEvent};
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
  pub fn subscribe_live_event_notifications(&self) -> mpsc::UnboundedReceiver<()> {
    let (sender, receiver) = mpsc::unbounded_channel();
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
    event: &PreparedDashboardEvent,
  ) -> Result<CommitOutcome> {
    let sequence = sequence_to_i64(identity.sequence, "PostgreSQL")?;
    self.lock_write().transaction(|conn| {
      diesel::sql_query("SELECT pg_advisory_xact_lock(hashtextextended($1, 0))")
        .bind::<Text, _>(&event.live.run_id)
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
      if let Some(outcome) = duplicate_outcome(identity, &event.live.run_id, sequence, stored)? {
        return Ok(outcome);
      }

      verify_sequence(conn, &event.live.run_id, sequence, &identity.event_id)?;
      let retention = retention_on(conn)?;
      apply_event(conn, &event.persisted, retention)?;

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
          .with_seq(live_event_id_to_u64(live_event_id)?),
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
    })
  }

  pub fn latest_live_event_id(&self) -> Result<u64> {
    let mut conn = self.lock_read();
    let id = live_events::table
      .select(max(live_events::id))
      .first::<Option<i64>>(&mut *conn)?
      .unwrap_or_default();
    live_event_id_to_u64(id)
  }

  pub fn live_events_after(&self, after_id: u64, limit: usize) -> Result<Vec<StoredLiveEvent>> {
    let after_id = i64::try_from(after_id).unwrap_or(i64::MAX);
    let limit = i64::try_from(limit).unwrap_or(i64::MAX);
    let mut conn = self.lock_read();
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

fn listen_for_live_events(database_url: &str, sender: &mpsc::UnboundedSender<()>) -> Result<()> {
  let mut client = super::database::connect_driver(database_url)?;
  client.batch_execute("LISTEN stove_live_events")?;
  let mut notifications = client.notifications();
  while !sender.is_closed() {
    if notifications
      .timeout_iter(Duration::from_secs(1))
      .next()?
      .is_some()
      && sender.send(()).is_err()
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
