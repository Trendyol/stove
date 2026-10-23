use super::super::lifecycle::{IdleTimeout, SWEEP_BATCH_SIZE};
use super::SqliteBackend;
use super::distributed::append_live_event;
use crate::error::Result;
use crate::ingest::LiveDashboardEvent;
use crate::storage::schema::sqlite::{dashboard_settings, runs};
use diesel::prelude::*;
use diesel::sql_types::Text;

pub(super) fn idle_timeout_on(conn: &mut SqliteConnection) -> Result<IdleTimeout> {
  let value = dashboard_settings::table
    .find(IdleTimeout::SETTING_KEY)
    .select(dashboard_settings::setting_value)
    .first::<String>(conn)
    .optional()?;
  IdleTimeout::from_stored(value.as_deref())
}

pub(super) fn database_now(conn: &mut SqliteConnection) -> Result<String> {
  Ok(
    diesel::select(diesel::dsl::sql::<Text>(
      "strftime('%Y-%m-%dT%H:%M:%fZ', 'now')",
    ))
    .get_result(conn)?,
  )
}

pub(super) fn touch_run(conn: &mut SqliteConnection, run_id: &str) -> Result<()> {
  let now = database_now(conn)?;
  diesel::update(runs::table.find(run_id))
    .set(runs::last_activity_at.eq(now))
    .execute(conn)?;
  Ok(())
}

impl SqliteBackend {
  pub fn initialize_idle_timeout(&self, timeout: IdleTimeout) -> Result<()> {
    let mut db = self.lock_write();
    let conn = db.conn();
    diesel::insert_into(dashboard_settings::table)
      .values((
        dashboard_settings::setting_key.eq(IdleTimeout::SETTING_KEY),
        dashboard_settings::setting_value.eq(timeout.seconds().to_string()),
      ))
      .on_conflict(dashboard_settings::setting_key)
      .do_nothing()
      .execute(conn)?;
    Ok(())
  }

  pub fn update_idle_timeout(&self, timeout: IdleTimeout) -> Result<()> {
    let mut db = self.lock_write();
    let conn = db.conn();
    diesel::insert_into(dashboard_settings::table)
      .values((
        dashboard_settings::setting_key.eq(IdleTimeout::SETTING_KEY),
        dashboard_settings::setting_value.eq(timeout.seconds().to_string()),
      ))
      .on_conflict(dashboard_settings::setting_key)
      .do_update()
      .set(dashboard_settings::setting_value.eq(timeout.seconds().to_string()))
      .execute(conn)?;
    Ok(())
  }

  pub fn expire_idle_runs(&self) -> Result<usize> {
    let mut db = self.lock_write();
    // SQLite permits one writer; IMMEDIATE also coordinates independent connections.
    db.conn().immediate_transaction(sweep_on)
  }
}
fn sweep_on(conn: &mut SqliteConnection) -> Result<usize> {
  let Some(cutoff) = idle_timeout_on(conn)?.expiry_cutoff(|| database_now(conn))? else {
    return Ok(0);
  };
  let candidates = runs::table
    .filter(runs::status.eq("RUNNING"))
    .filter(runs::last_activity_at.le(&cutoff))
    .order((runs::last_activity_at, runs::id))
    .limit(SWEEP_BATCH_SIZE)
    .select(runs::id)
    .load::<String>(conn)?;
  // The enclosing IMMEDIATE transaction holds SQLite's writer lock from
  // candidate selection through deletion and notification publication.
  super::writes::delete_runs_on(conn, &candidates)?;
  for run_id in &candidates {
    let live = LiveDashboardEvent::run_deleted(run_id);
    append_live_event(conn, &uuid::Uuid::new_v4().to_string(), &live)?;
  }
  Ok(candidates.len())
}
