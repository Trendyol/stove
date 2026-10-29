use super::{ProfileQuery, ProfileReport, StoredChunk, compress, decompress, invalid, limits};
use crate::{error::Result, http::server::AppState};
use axum::{
  Json, Router,
  extract::{DefaultBodyLimit, Multipart, Path, Query, State},
  http::{HeaderMap, StatusCode},
  routing::{get, post},
};
use serde::Deserialize;
use sha2::{Digest, Sha256};
use std::sync::Arc;
static CONVERTERS: tokio::sync::Semaphore =
  tokio::sync::Semaphore::const_new(limits::MAX_CONCURRENT_CONVERSIONS);

pub fn routes() -> Router<AppState> {
  Router::new()
    .route(
      "/ingest",
      post(ingest).layer(DefaultBodyLimit::max(limits::MAX_UPLOAD_BYTES)),
    )
    .route("/api/v1/runs/{run_id}/profiles", get(get_profiles))
    .route(
      "/profiling-notices.txt",
      get(|| async { include_str!("../../profiling-bridge/THIRD_PARTY_NOTICES") }),
    )
}
#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct IngestQuery {
  name: String,
  from: i64,
  until: i64,
  format: String,
  sample_rate: Option<i64>,
}
fn header(headers: &HeaderMap, key: &str) -> Result<String> {
  let value = headers
    .get(key)
    .and_then(|v| v.to_str().ok())
    .filter(|v| !v.trim().is_empty() && v.len() <= 256)
    .ok_or_else(|| invalid(format!("Missing or invalid {key}")))?;
  Ok(value.to_string())
}
async fn ingest(
  State(state): State<AppState>,
  Query(q): Query<IngestQuery>,
  headers: HeaderMap,
  multipart: Multipart,
) -> Result<StatusCode> {
  let upload = ProfileUpload::from_request(q, &headers)?;
  // A run-start dashboard event may still be in flight. 503 makes the stock agent retry.
  if state.repository.get_run(&upload.run_id)?.is_none() {
    return Ok(StatusCode::SERVICE_UNAVAILABLE);
  }
  let Ok(permit) = CONVERTERS.try_acquire() else {
    return Ok(StatusCode::SERVICE_UNAVAILABLE);
  };
  let (data, labels) = read_parts(multipart).await?;
  let repo = Arc::clone(&state.repository);
  tokio::task::spawn_blocking(move || {
    // Keep the limit even if the HTTP client disconnects during conversion.
    let _permit = permit;
    repo.save_profile(&upload.into_chunk(&data, &labels)?)
  })
  .await
  .map_err(|_| invalid("Profile conversion task failed"))??;
  Ok(StatusCode::OK)
}
struct ProfileUpload {
  query: IngestQuery,
  run_id: String,
  service: String,
  instance: String,
  rate: i64,
}

impl ProfileUpload {
  fn from_request(q: IngestQuery, headers: &HeaderMap) -> Result<Self> {
    if q.format != "jfr" {
      return Err(invalid("Only Pyroscope format=jfr is supported"));
    }
    if q.from < 0 || q.until < q.from || q.until - q.from > 3600 || q.until > i64::MAX / 1000 {
      return Err(invalid("Invalid profile time window (maximum one hour)"));
    }
    let rate = q.sample_rate.unwrap_or(100);
    if !(1..=100_000).contains(&rate) {
      return Err(invalid("Invalid sampleRate"));
    }
    let run_id = header(headers, "x-stove-run-id")?;
    let service = header(headers, "x-stove-service-name")
      .unwrap_or_else(|_| q.name.split('{').next().unwrap_or_default().to_owned());
    let instance = header(headers, "x-stove-instance-id")?;
    if service.is_empty() || service.len() > 256 {
      return Err(invalid("Invalid service name"));
    }
    Ok(Self {
      query: q,
      run_id,
      service,
      instance,
      rate,
    })
  }

  fn into_chunk(self, data: &[u8], labels: &[u8]) -> Result<StoredChunk> {
    let Self {
      query: q,
      run_id,
      service,
      instance,
      rate,
    } = self;
    let data = decompress(data)?;
    let labels = decompress(labels)?;
    let mut hash = Sha256::new();
    // Length-delimited identity prevents ambiguous concatenations; retries are idempotent.
    for item in [
      run_id.as_bytes(),
      service.as_bytes(),
      instance.as_bytes(),
      q.from.to_string().as_bytes(),
      q.until.to_string().as_bytes(),
      rate.to_string().as_bytes(),
      data.as_slice(),
      labels.as_slice(),
    ] {
      hash.update((item.len() as u64).to_le_bytes());
      hash.update(item);
    }
    let id = format!("{:x}", hash.finalize());
    let recording = super::bridge::convert(&data, &labels, q.from, q.until, rate)?;
    let encoded = serde_json::to_vec(&recording)?;
    if encoded.len() > limits::MAX_DECODED_BYTES {
      return Err(invalid("Normalized profile exceeds 32 MiB"));
    }
    let payload = compress(&encoded)?;
    if payload.len() > limits::MAX_COMPRESSED_BYTES {
      return Err(invalid("Compressed profile exceeds 8 MiB"));
    }
    Ok(StoredChunk {
      id,
      run_id,
      service,
      instance,
      from_ms: q.from * 1000,
      until_ms: q.until * 1000,
      payload,
    })
  }
}

async fn read_parts(mut multipart: Multipart) -> Result<(Vec<u8>, Vec<u8>)> {
  let mut jfr = None;
  let mut labels = None;
  while let Some(field) = multipart
    .next_field()
    .await
    .map_err(|_| invalid("Invalid profiling multipart body"))?
  {
    let name = field.name().unwrap_or_default().to_string();
    let bytes = field
      .bytes()
      .await
      .map_err(|_| invalid("Invalid or oversized profiling field"))?;
    match name.as_str() {
      "jfr" if jfr.is_none() => jfr = Some(bytes.to_vec()),
      "labels" if labels.is_none() => labels = Some(bytes.to_vec()),
      _ => return Err(invalid("Unknown or duplicate profiling multipart field")),
    }
  }
  let data = jfr.ok_or_else(|| invalid("Missing jfr field"))?;
  Ok((data, labels.unwrap_or_default()))
}

#[utoipa::path(get,path="/api/v1/runs/{run_id}/profiles",params(("run_id"=String,Path,description="Exact Stove run"), ProfileQuery),responses((status=200,body=ProfileReport)))]
pub async fn get_profiles(
  State(state): State<AppState>,
  Path(run_id): Path<String>,
  Query(query): Query<ProfileQuery>,
) -> Result<Json<ProfileReport>> {
  let repository = state.repository;
  tokio::task::spawn_blocking(move || super::report(&repository, &run_id, &query))
    .await
    .map_err(|_| invalid("Profile query failed"))?
    .map(Json)
}
