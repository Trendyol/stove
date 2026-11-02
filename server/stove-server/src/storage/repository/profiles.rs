// Diesel QueryableByName emits redundant field names.
#![allow(clippy::redundant_field_names)]

use super::{Backend, Repository};
use crate::error::Result;
use crate::profiling::{StoredChunk, limits::MAX_QUERY_CHUNKS};

pub(super) const MAX_PROFILE_CHUNKS: i64 = 256;
const MAX_PROFILE_BYTES: i64 = 64 * 1024 * 1024;

// Queries rank matching windows within each stream, then interleave streams. Only
// IDs are materialized here; payloads are fetched one at a time within the decode budget.
pub(super) const PROFILE_IDS_SQL: &str = "
  SELECT id FROM (
    SELECT id, service, instance,
      ROW_NUMBER() OVER (PARTITION BY service, instance ORDER BY until_ms DESC, id DESC) AS rank
    FROM profiles WHERE run_id = $1
      AND (CAST($2 AS TEXT) IS NULL OR service = $2) AND (CAST($3 AS TEXT) IS NULL OR instance = $3)
      AND (CAST($4 AS BIGINT) IS NULL OR until_ms > $4) AND (CAST($5 AS BIGINT) IS NULL OR from_ms < $5)
  ) ranked ORDER BY rank, service, instance LIMIT $6";

#[derive(diesel::QueryableByName)]
pub(super) struct ProfileId {
  #[diesel(sql_type = diesel::sql_types::Text)]
  pub id: String,
}

/// Input is newest first. Count only eligible per-stream candidates toward the
/// shared byte budget; once that budget is exhausted, evict older candidates.
pub(super) fn expired_profile_ids(rows: Vec<(String, String, String, i64)>) -> Vec<String> {
  let mut bytes = 0i64;
  let mut counts = std::collections::HashMap::new();
  rows
    .into_iter()
    .filter_map(|(id, service, instance, size)| {
      let count = counts.entry((service, instance)).or_insert(0);
      *count += 1;
      if *count > MAX_PROFILE_CHUNKS {
        return Some(id);
      }
      bytes = bytes.saturating_add(size);
      (bytes > MAX_PROFILE_BYTES).then_some(id)
    })
    .collect()
}

impl Repository {
  pub fn save_profile(&self, chunk: &StoredChunk) -> Result<()> {
    self.with_backend("save_profile", "write", |backend| match backend {
      Backend::Sqlite(db) => db.save_profile(chunk),
      Backend::Postgres(db) => db.save_profile(chunk),
    })
  }
  pub fn profile_chunks(&self, run_id: &str) -> Result<Vec<StoredChunk>> {
    self
      .profile_chunk_ids(run_id, &crate::profiling::ProfileQuery::default())?
      .into_iter()
      .take(MAX_QUERY_CHUNKS)
      .filter_map(|id| self.profile_chunk(&id).transpose())
      .collect()
  }
  pub(crate) fn profile_chunk_ids(
    &self,
    run_id: &str,
    query: &crate::profiling::ProfileQuery,
  ) -> Result<Vec<String>> {
    self.with_backend("profile_chunk_ids", "read", |backend| match backend {
      Backend::Sqlite(db) => db.profile_chunk_ids(run_id, query),
      Backend::Postgres(db) => db.profile_chunk_ids(run_id, query),
    })
  }
  pub(crate) fn profile_chunk(&self, id: &str) -> Result<Option<StoredChunk>> {
    self.with_backend("profile_chunk", "read", |backend| match backend {
      Backend::Sqlite(db) => db.profile_chunk(id),
      Backend::Postgres(db) => db.profile_chunk(id),
    })
  }
}

#[cfg(test)]
mod tests {
  use super::*;
  #[test]
  fn excluded_stream_history_does_not_consume_the_shared_byte_budget() {
    let mut rows: Vec<_> = (0..256)
      .map(|i| (i.to_string(), "a".into(), "one".into(), 200 * 1024))
      .collect();
    rows.push(("excluded".into(), "a".into(), "one".into(), 8 * 1024 * 1024));
    rows.push(("retained".into(), "b".into(), "one".into(), 8 * 1024 * 1024));
    assert_eq!(expired_profile_ids(rows), vec!["excluded"]);
  }
  #[test]
  fn byte_budget_remains_shared_across_streams() {
    let rows = (0..9)
      .map(|i| (i.to_string(), i.to_string(), "one".into(), 8 * 1024 * 1024))
      .collect();
    assert_eq!(expired_profile_ids(rows), vec!["8"]);
  }
}
