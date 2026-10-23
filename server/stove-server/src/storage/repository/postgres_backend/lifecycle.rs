use super::super::lifecycle::{IdleTimeout, SWEEP_BATCH_SIZE};
use super::PostgresBackend;
use super::distributed::append_live_event;
use crate::error::{AppError, Result};
use crate::ingest::LiveDashboardEvent;
use crate::storage::schema::postgres::{dashboard_settings, runs};
use diesel::connection::SimpleConnection;
use diesel::prelude::*;
use diesel::sql_types::Text;

pub(super) fn idle_timeout_on(conn: &mut PgConnection) -> Result<IdleTimeout> {
  let value = dashboard_settings::table
    .find(IdleTimeout::SETTING_KEY)
    .select(dashboard_settings::setting_value)
    .first::<String>(conn)
    .optional()?;
  IdleTimeout::from_stored(value.as_deref())
}

pub(super) fn database_now(conn: &mut PgConnection) -> Result<String> {
  Ok(
    diesel::select(diesel::dsl::sql::<Text>(
      "to_char(clock_timestamp() AT TIME ZONE 'UTC', 'YYYY-MM-DD\"T\"HH24:MI:SS.MS\"Z\"')",
    ))
    .get_result(conn)?,
  )
}

pub(super) fn touch_run(conn: &mut PgConnection, run_id: &str) -> Result<()> {
  let now = database_now(conn)?;
  diesel::update(runs::table.find(run_id))
    .set(runs::last_activity_at.eq(now))
    .execute(conn)?;
  Ok(())
}

pub(super) fn lock_run(conn: &mut PgConnection, run_id: &str) -> Result<()> {
  diesel::sql_query("SELECT pg_advisory_xact_lock(hashtextextended($1, 0))")
    .bind::<Text, _>(run_id)
    .execute(conn)?;
  Ok(())
}
impl PostgresBackend {
  pub fn initialize_idle_timeout(&self, timeout: IdleTimeout) -> Result<()> {
    let mut connection = self.checkout_write()?;
    let conn = &mut *connection;
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
    let mut connection = self.checkout_write()?;
    let conn = &mut *connection;
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
    // Owned, unpooled session: dropping it releases the sweep lock on every exit,
    // including unwind. A lost session cannot keep executing sweep transactions.
    let mut conn = PgConnection::establish(&sweep_connection_url(self.database_url()))?;
    conn.batch_execute("SET statement_timeout = '10s'; SET lock_timeout = '2s'")?;
    let acquired = diesel::select(diesel::dsl::sql::<diesel::sql_types::Bool>(
      "pg_try_advisory_lock(1937012086, 2)",
    ))
    .get_result::<bool>(&mut conn)?;
    if !acquired {
      return Ok(0);
    }
    sweep_on(&mut conn)
  }
}
fn sweep_on(conn: &mut PgConnection) -> Result<usize> {
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
  let mut count = 0;
  for run_id in candidates {
    count += conn.transaction::<usize, AppError, _>(|conn| {
      lock_run(conn, &run_id)?;
      delete_idle_run(conn, &run_id, &cutoff)
    })?;
  }
  Ok(count)
}

// Preserve URL options such as search_path, while bounding connection establishment.
fn sweep_connection_url(database_url: &str) -> String {
  if let Ok(mut url) = reqwest::Url::parse(database_url) {
    // Keep the raw query encoding: libpq does not decode form-encoded '+' as space.
    // libpq uses the last value when a connection parameter occurs more than once.
    let query = url.query().map_or_else(
      || "connect_timeout=2".to_owned(),
      |query| format!("{query}&connect_timeout=2"),
    );
    url.set_query(Some(&query));
    url.into()
  } else {
    format!("{database_url} connect_timeout=2")
  }
}

#[test]
fn sweep_connection_preserves_libpq_options_and_bounds_connect_time() {
  assert_eq!(
    sweep_connection_url(
      "postgresql://localhost/stove?options=-c%20search_path%3Dstove&connect_timeout=30"
    ),
    "postgresql://localhost/stove?options=-c%20search_path%3Dstove&connect_timeout=30&connect_timeout=2"
  );
  assert_eq!(
    sweep_connection_url("host=localhost dbname=stove"),
    "host=localhost dbname=stove connect_timeout=2"
  );
}

/// Recheck eligibility inside the caller's locked transaction.
fn delete_idle_run(conn: &mut PgConnection, run_id: &str, cutoff: &str) -> Result<usize> {
  let changed = diesel::delete(
    runs::table
      .find(run_id)
      .filter(runs::status.eq("RUNNING"))
      .filter(runs::last_activity_at.le(cutoff)),
  )
  .execute(conn)?;
  if changed > 0 {
    // The nullable outbox reference lets this notification outlive the deleted run.
    let live = LiveDashboardEvent::run_deleted(run_id);
    append_live_event(conn, &uuid::Uuid::new_v4().to_string(), &live)?;
  }
  Ok(changed)
}
