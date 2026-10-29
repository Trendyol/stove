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
  format: Option<String>,
  spy_name: Option<String>,
  sample_rate: Option<i64>,
}
/// The two supported push contracts use different time units and multipart fields.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum UploadFormat {
  Jfr,
  GoPprof,
}

impl UploadFormat {
  fn from_query(q: &IngestQuery) -> Result<Self> {
    match (q.format.as_deref(), q.spy_name.as_deref()) {
      (Some("jfr"), spy) if spy != Some("gospy") => Ok(Self::Jfr),
      (None | Some("pprof"), Some("gospy")) => Ok(Self::GoPprof),
      _ => Err(invalid(
        "Supported uploads: format=jfr or Go pprof with spyName=gospy",
      )),
    }
  }

  fn validate_window(self, from: i64, until: i64) -> Result<()> {
    let units_per_second = match self {
      Self::Jfr => 1,
      Self::GoPprof => 1_000_000_000,
    };
    if from < 0
      || until < from
      || until - from > 3600 * units_per_second
      || (self == Self::Jfr && until > i64::MAX / 1000)
    {
      return Err(invalid("Invalid profile time window (maximum one hour)"));
    }
    Ok(())
  }

  fn timestamp_ms(self, timestamp: i64) -> i64 {
    match self {
      Self::Jfr => timestamp * 1000,
      Self::GoPprof => timestamp / 1_000_000,
    }
  }

  fn default_sample_rate(self) -> i64 {
    match self {
      Self::Jfr => 100,
      Self::GoPprof => 0,
    }
  }

  fn minimum_sample_rate(self) -> i64 {
    match self {
      Self::Jfr => 1,
      Self::GoPprof => 0,
    }
  }

  fn multipart_fields(self) -> (&'static str, &'static str) {
    match self {
      Self::Jfr => ("jfr", "labels"),
      Self::GoPprof => ("profile", "sample_type_config"),
    }
  }
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
  let (data, metadata) = read_parts(multipart, upload.format).await?;
  let repo = Arc::clone(&state.repository);
  tokio::task::spawn_blocking(move || {
    // Keep the limit even if the HTTP client disconnects during conversion.
    let _permit = permit;
    repo.save_profile(&upload.into_chunk(&data, &metadata)?)
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
  format: UploadFormat,
}

impl ProfileUpload {
  fn from_request(q: IngestQuery, headers: &HeaderMap) -> Result<Self> {
    let format = UploadFormat::from_query(&q)?;
    format.validate_window(q.from, q.until)?;
    let rate = q.sample_rate.unwrap_or(format.default_sample_rate());
    if !(format.minimum_sample_rate()..=100_000).contains(&rate) {
      return Err(invalid("Invalid sampleRate"));
    }
    let run_id = header(headers, "x-stove-run-id")?;
    let service = if headers.contains_key("x-stove-service-name") {
      header(headers, "x-stove-service-name")?
    } else {
      let name = q.name.split('{').next().unwrap_or_default();
      if name.trim().is_empty() || name.len() > 256 || name.chars().any(char::is_control) {
        return Err(invalid("Invalid service name"));
      }
      name.to_owned()
    };
    let instance = header(headers, "x-stove-instance-id")?;
    Ok(Self {
      query: q,
      run_id,
      service,
      instance,
      rate,
      format,
    })
  }

  fn into_chunk(self, data: &[u8], metadata: &[u8]) -> Result<StoredChunk> {
    let Self {
      query: q,
      run_id,
      service,
      instance,
      rate,
      format,
    } = self;
    let data = decompress(data)?;
    let metadata = decompress(metadata)?;
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
      metadata.as_slice(),
    ] {
      hash.update((item.len() as u64).to_le_bytes());
      hash.update(item);
    }
    if format == UploadFormat::GoPprof {
      hash.update(b"go-pprof");
    }
    let id = format!("{:x}", hash.finalize());
    let recording = match format {
      UploadFormat::GoPprof => super::bridge::convert_pprof(&data, &metadata, rate)?,
      UploadFormat::Jfr => super::bridge::convert(&data, &metadata, q.from, q.until, rate)?,
    };
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
      from_ms: format.timestamp_ms(q.from),
      until_ms: format.timestamp_ms(q.until),
      payload,
    })
  }
}

async fn read_parts(mut multipart: Multipart, format: UploadFormat) -> Result<(Vec<u8>, Vec<u8>)> {
  let (data_name, metadata_name) = format.multipart_fields();
  let mut data = None;
  let mut metadata = None;
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
      n if n == data_name && data.is_none() => data = Some(bytes.to_vec()),
      n if n == metadata_name && metadata.is_none() => metadata = Some(bytes.to_vec()),
      _ => return Err(invalid("Unknown or duplicate profiling multipart field")),
    }
  }
  let data = data.ok_or_else(|| invalid(format!("Missing {data_name} field")))?;
  Ok((data, metadata.unwrap_or_default()))
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

#[cfg(test)]
mod tests {
  use super::*;

  #[test]
  fn an_invalid_explicit_service_does_not_fall_back_to_the_app_name() {
    let mut headers = HeaderMap::new();
    headers.insert("x-stove-run-id", "run".parse().unwrap());
    headers.insert("x-stove-instance-id", "go".parse().unwrap());
    let query = || IngestQuery {
      name: "app{env=test}".into(),
      from: 0,
      until: 1,
      format: None,
      spy_name: Some("gospy".into()),
      sample_rate: None,
    };
    assert_eq!(
      ProfileUpload::from_request(query(), &headers)
        .unwrap()
        .service,
      "app"
    );
    for value in [" ".to_owned(), "x".repeat(257)] {
      headers.insert("x-stove-service-name", value.parse().unwrap());
      assert!(ProfileUpload::from_request(query(), &headers).is_err());
    }
  }

  #[test]
  fn formats_bound_and_convert_their_own_timestamp_units() {
    for (format, scale) in [
      (UploadFormat::Jfr, 1),
      (UploadFormat::GoPprof, 1_000_000_000),
    ] {
      let from = 1_700_000_000 * scale;
      let until = from + 3600 * scale;
      format.validate_window(from, until).unwrap();
      assert_eq!(format.timestamp_ms(from), 1_700_000_000_000);
      assert!(format.validate_window(from, until + 1).is_err());
      assert!(format.validate_window(-1, until).is_err());
      assert!(format.validate_window(until, from).is_err());
    }
    assert!(
      UploadFormat::Jfr
        .validate_window(i64::MAX, i64::MAX)
        .is_err()
    );
  }

  #[test]
  fn native_uploads_validate_format_time_units_and_unspecified_sampling() {
    let mut headers = HeaderMap::new();
    headers.insert("x-stove-run-id", "run".parse().unwrap());
    headers.insert("x-stove-instance-id", "go".parse().unwrap());
    let query = || IngestQuery {
      name: "app".into(),
      from: 1_700_000_000_000_000_000,
      until: 1_700_000_010_000_000_000,
      format: None,
      spy_name: Some("gospy".into()),
      sample_rate: Some(0),
    };
    let upload = ProfileUpload::from_request(query(), &headers).unwrap();
    assert_eq!(upload.format, UploadFormat::GoPprof);
    assert_eq!(upload.rate, 0);
    for q in [
      IngestQuery {
        format: Some("jfr".into()),
        ..query()
      },
      IngestQuery {
        spy_name: None,
        ..query()
      },
      IngestQuery {
        from: -1,
        ..query()
      },
      IngestQuery {
        until: i64::MAX,
        ..query()
      },
      IngestQuery {
        until: 0,
        ..query()
      },
      IngestQuery {
        sample_rate: Some(-1),
        ..query()
      },
    ] {
      assert!(ProfileUpload::from_request(q, &headers).is_err());
    }
  }
}
