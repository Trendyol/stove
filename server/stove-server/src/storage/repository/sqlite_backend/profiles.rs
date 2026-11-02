use super::super::profiles::{PROFILE_IDS_SQL, ProfileId, expired_profile_ids};
use super::SqliteBackend;
use crate::error::{AppError, Result};
use crate::profiling::StoredChunk;
use crate::storage::schema::sqlite::{profiles, runs};
use diesel::prelude::*;
impl SqliteBackend {
  pub fn save_profile(&self, chunk: &StoredChunk) -> Result<()> {
    let mut db = self.lock_write();
    let conn = db.conn();
    conn.transaction(|conn| {
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
        .select((
          profiles::id,
          profiles::service,
          profiles::instance,
          profiles::byte_size,
        ))
        .load::<(String, String, String, i64)>(conn)?;
      let expired = expired_profile_ids(rows);
      diesel::delete(profiles::table.filter(profiles::id.eq_any(expired))).execute(conn)?;
      Ok(())
    })
  }
  pub fn profile_chunk(&self, id: &str) -> Result<Option<StoredChunk>> {
    let mut db = self.lock_read();
    let conn = db.conn();
    let rows = profiles::table
      .filter(profiles::id.eq(id))
      .select((
        profiles::id,
        profiles::run_id,
        profiles::service,
        profiles::instance,
        profiles::from_ms,
        profiles::until_ms,
        profiles::payload,
      ))
      .first::<StoredChunk>(conn)
      .optional()?;
    Ok(rows)
  }
  pub fn profile_chunk_ids(
    &self,
    run_id: &str,
    query: &crate::profiling::ProfileQuery,
  ) -> Result<Vec<String>> {
    use diesel::sql_types::{BigInt, Nullable, Text};
    let mut db = self.lock_read();
    let conn = db.conn();
    Ok(
      diesel::sql_query(PROFILE_IDS_SQL)
        .bind::<Text, _>(run_id)
        .bind::<Nullable<Text>, _>(query.service.as_deref())
        .bind::<Nullable<Text>, _>(query.instance.as_deref())
        .bind::<Nullable<BigInt>, _>(query.from_ms)
        .bind::<Nullable<BigInt>, _>(query.until_ms)
        .bind::<BigInt, _>(
          i64::try_from(crate::profiling::limits::MAX_QUERY_CHUNKS + 1)
            .expect("query limit fits i64"),
        )
        .load::<ProfileId>(conn)?
        .into_iter()
        .map(|row| row.id)
        .collect(),
    )
  }
}
