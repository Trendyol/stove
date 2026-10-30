use super::{Backend, Repository};
use crate::error::Result;
use crate::profiling::StoredChunk;

pub(super) const MAX_PROFILE_CHUNKS: i64 = 256;
const MAX_PROFILE_BYTES: i64 = 64 * 1024 * 1024;

/// Input is newest first. Once either budget is exhausted, evict all older chunks.
pub(super) fn expired_profile_ids(rows: Vec<(String, i64)>) -> Vec<String> {
  let mut bytes = 0i64;
  rows
    .into_iter()
    .zip(0..)
    .filter_map(|((id, size), index)| {
      bytes = bytes.saturating_add(size);
      (index >= MAX_PROFILE_CHUNKS || bytes > MAX_PROFILE_BYTES).then_some(id)
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
    self.with_backend("profile_chunks", "read", |backend| match backend {
      Backend::Sqlite(db) => db.profile_chunks(run_id),
      Backend::Postgres(db) => db.profile_chunks(run_id),
    })
  }
}
