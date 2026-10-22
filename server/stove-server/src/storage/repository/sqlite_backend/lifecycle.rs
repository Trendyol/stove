use super::super::lifecycle::{ExpiryWindow, IdleTimeout, RecoveryMode, SWEEP_BATCH_SIZE};
use super::SqliteBackend;
use super::distributed::append_live_event;
use crate::error::{AppError, Result};
use crate::ingest::{LiveDashboardEvent, LiveDashboardPayload, LiveRunLifecyclePayload};
use crate::storage::models::{Run, Test};
use crate::storage::repository::mapping::{RunRow, TestRow};
use crate::storage::schema::sqlite::{dashboard_settings, runs, tests};
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

/// Called only after deduplication and sequence checks, in the event transaction.
pub(super) fn recover_run(
  conn: &mut SqliteConnection,
  run_id: &str,
  mode: RecoveryMode,
) -> Result<bool> {
  let recovered = diesel::update(
    runs::table
      .find(run_id)
      .filter(runs::status.eq("ABANDONED")),
  )
  .set((
    runs::status.eq("RUNNING"),
    runs::abandoned_at.eq(None::<String>),
  ))
  .execute(conn)?
    > 0;
  if recovered && mode == RecoveryMode::ResumeTests {
    diesel::update(
      tests::table
        .filter(tests::run_id.eq(run_id))
        .filter(tests::status.eq("ABANDONED")),
    )
    .set(tests::status.eq("RUNNING"))
    .execute(conn)?;
  }
  Ok(recovered)
}

pub(super) fn touch_run(conn: &mut SqliteConnection, run_id: &str) -> Result<()> {
  let now = database_now(conn)?;
  diesel::update(runs::table.find(run_id))
    .set(runs::last_activity_at.eq(now))
    .execute(conn)?;
  Ok(())
}

pub(super) fn publish_lifecycle(conn: &mut SqliteConnection, run_id: &str) -> Result<()> {
  let run = runs::table
    .find(run_id)
    .select(runs::all_columns)
    .first::<RunRow<String>>(conn)?;
  let tests = tests::table
    .filter(tests::run_id.eq(run_id))
    .order(tests::id)
    .select(tests::all_columns)
    .load::<TestRow>(conn)?
    .into_iter()
    .map(Test::try_from)
    .collect::<Result<Vec<_>>>()?;
  let live = LiveDashboardEvent::new(
    run_id,
    LiveDashboardPayload::RunLifecycleChanged(LiveRunLifecyclePayload {
      run: Run::try_from(run)?,
      tests,
    }),
  );
  append_live_event(conn, &uuid::Uuid::new_v4().to_string(), &live)?;
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
  let Some(window) = idle_timeout_on(conn)?.expiry_window(|| database_now(conn))? else {
    return Ok(0);
  };
  let candidates = runs::table
    .filter(runs::status.eq("RUNNING"))
    .filter(runs::last_activity_at.le(&window.cutoff))
    .order((runs::last_activity_at, runs::id))
    .limit(SWEEP_BATCH_SIZE)
    .select(runs::id)
    .load::<String>(conn)?;
  let mut count = 0;
  for run_id in candidates {
    count += conn.transaction::<usize, AppError, _>(|conn| abandon_run(conn, &run_id, &window))?;
  }
  Ok(count)
}

/// Recheck eligibility inside the caller's locked transaction.
fn abandon_run(conn: &mut SqliteConnection, run_id: &str, window: &ExpiryWindow) -> Result<usize> {
  let changed = diesel::update(
    runs::table
      .find(run_id)
      .filter(runs::status.eq("RUNNING"))
      .filter(runs::last_activity_at.le(&window.cutoff)),
  )
  .set((
    runs::status.eq("ABANDONED"),
    runs::abandoned_at.eq(Some(&window.abandoned_at)),
  ))
  .execute(conn)?;
  if changed == 0 {
    return Ok(0);
  }
  diesel::update(
    tests::table
      .filter(tests::run_id.eq(run_id))
      .filter(tests::status.eq("RUNNING")),
  )
  .set(tests::status.eq("ABANDONED"))
  .execute(conn)?;
  publish_lifecycle(conn, run_id)?;
  Ok(1)
}
