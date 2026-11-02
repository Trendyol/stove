//! Run-scoped continuous profiles. The embedded converter uses Pyroscope's JFR and Go pprof contracts.
mod bridge;
pub(crate) mod http;
pub(crate) mod limits;
mod query;
pub use http::routes;
pub use query::{ProfileQuery, ProfileReport, report};
use serde::{Deserialize, Serialize};
use utoipa::ToSchema;

#[derive(Clone, Debug, diesel::Queryable)]
pub struct StoredChunk {
  pub id: String,
  pub run_id: String,
  pub service: String,
  pub instance: String,
  pub from_ms: i64,
  pub until_ms: i64,
  pub payload: Vec<u8>,
}
#[derive(Clone, Debug, Deserialize, Serialize, ToSchema, PartialEq, Eq, PartialOrd, Ord)]
pub struct Frame {
  pub name: String,
  pub file: String,
  pub line: i64,
}
#[derive(Clone, Debug, Deserialize, Serialize, ToSchema)]
pub struct Sample {
  // Inline frames keep existing stored recordings readable.
  #[serde(default, skip_serializing_if = "Vec::is_empty")]
  pub frames: Vec<Frame>,
  #[serde(default, skip_serializing_if = "Vec::is_empty")]
  pub frame_ids: Vec<usize>,
  pub value: i64,
  #[serde(default = "one", skip_serializing_if = "is_one")]
  #[schema(value_type = usize, minimum = 1)]
  pub count: std::num::NonZeroUsize,
  pub labels: std::collections::BTreeMap<String, String>,
}
#[derive(Clone, Debug, Deserialize, Serialize, ToSchema)]
pub struct Profile {
  pub metric: String,
  pub unit: String,
  pub samples: Vec<Sample>,
}
#[derive(Clone, Debug, Deserialize, Serialize)]
pub struct Recording {
  #[serde(default, skip_serializing_if = "Vec::is_empty")]
  pub frame_table: Vec<Frame>,
  pub profiles: Vec<Profile>,
  pub sample_rate: i64,
}

pub(crate) fn invalid(message: impl Into<String>) -> crate::error::AppError {
  crate::error::AppError::InvalidEvent(message.into())
}
pub(crate) fn decompress(bytes: &[u8]) -> crate::error::Result<Vec<u8>> {
  decompress_bounded(bytes, limits::MAX_DECODED_BYTES)?
    .ok_or_else(|| invalid("Decompressed profile exceeds 32 MiB"))
}
pub(crate) fn decompress_bounded(
  bytes: &[u8],
  limit: usize,
) -> crate::error::Result<Option<Vec<u8>>> {
  use std::io::Read;
  let limit = limit.min(limits::MAX_DECODED_BYTES);
  if bytes.starts_with(&[0x1f, 0x8b]) {
    let mut out = Vec::new();
    flate2::read::GzDecoder::new(bytes)
      .take(limit as u64 + 1)
      .read_to_end(&mut out)
      .map_err(|_| invalid("Invalid gzip profile"))?;
    Ok((out.len() <= limit).then_some(out))
  } else {
    Ok((bytes.len() <= limit).then(|| bytes.to_vec()))
  }
}

pub(crate) fn compress(bytes: &[u8]) -> crate::error::Result<Vec<u8>> {
  use std::io::Write;
  let mut out = flate2::write::GzEncoder::new(Vec::new(), flate2::Compression::fast());
  out
    .write_all(bytes)
    .map_err(|_| invalid("Cannot compress profile"))?;
  out.finish().map_err(|_| invalid("Cannot compress profile"))
}

fn one() -> std::num::NonZeroUsize {
  std::num::NonZeroUsize::new(1).unwrap()
}
#[allow(clippy::trivially_copy_pass_by_ref)] // serde requires a reference predicate.
fn is_one(count: &std::num::NonZeroUsize) -> bool {
  count.get() == 1
}
