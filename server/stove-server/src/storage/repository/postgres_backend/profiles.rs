use super::super::profiles::{MAX_PROFILE_CHUNKS, expired_profile_ids};
use super::PostgresBackend;
use crate::error::{AppError, Result};
use crate::profiling::StoredChunk;
use crate::storage::schema::postgres::{profiles, runs};
use diesel::prelude::*;
impl PostgresBackend {
  pub fn save_profile(&self, chunk: &StoredChunk) -> Result<()> {
    let mut connection = self.checkout_write()?;
    let conn = &mut *connection;
    conn.transaction(|conn| {
      super::lifecycle::lock_run(conn, &chunk.run_id)?;
      let exists = runs::table
        .find(&chunk.run_id)
        .select(runs::id)
        .first::<String>(conn)
        .optional()?;
      if exists.is_none() {
        return Err(AppError::NotFound(
          "Profile run has not arrived or was deleted".into(),
        ));
      }
      diesel::insert_into(profiles::table)
        .values((
          profiles::id.eq(&chunk.id),
          profiles::run_id.eq(&chunk.run_id),
          profiles::service.eq(&chunk.service),
          profiles::instance.eq(&chunk.instance),
          profiles::from_ms.eq(chunk.from_ms),
          profiles::until_ms.eq(chunk.until_ms),
          profiles::payload.eq(&chunk.payload),
          profiles::byte_size.eq(
            i64::try_from(chunk.payload.len())
              .map_err(|_| crate::profiling::invalid("Profile too large"))?,
          ),
        ))
        .on_conflict(profiles::id)
        .do_nothing()
        .execute(conn)?;
      // Bound active runs too. Retain the newest windows within both budgets.
      let rows = profiles::table
        .filter(profiles::run_id.eq(&chunk.run_id))
        .order((profiles::until_ms.desc(), profiles::id.desc()))
        .select((profiles::id, profiles::byte_size))
        .load::<(String, i64)>(conn)?;
      let expired = expired_profile_ids(rows);
      diesel::delete(profiles::table.filter(profiles::id.eq_any(expired))).execute(conn)?;
      Ok(())
    })
  }
  pub fn profile_chunks(&self, run_id: &str) -> Result<Vec<StoredChunk>> {
    let mut connection = self.checkout_read()?;
    let conn = &mut *connection;
    let rows = profiles::table
      .filter(profiles::run_id.eq(run_id))
      .order((profiles::until_ms.desc(), profiles::id.desc()))
      .limit(MAX_PROFILE_CHUNKS)
      .select((
        profiles::id,
        profiles::run_id,
        profiles::service,
        profiles::instance,
        profiles::from_ms,
        profiles::until_ms,
        profiles::payload,
      ))
      .load::<StoredChunk>(conn)?;
    Ok(rows)
  }
}
