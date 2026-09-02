//! Conversion helpers between protobuf wire types and the dashboard's
//! internal event representation, plus the small `PreparedDashboardEvent`
//! struct shared by every preparer.

use std::collections::HashMap;

use tonic::Status;

use crate::error::AppError;
use crate::storage::models::RunStatus;

pub(super) fn extract_test_id(attributes: &HashMap<String, String>) -> Option<String> {
  [
    "x-stove-test-id",
    "X-Stove-Test-Id",
    "stove.test.id",
    "stove_test_id",
  ]
  .iter()
  .find_map(|key| attributes.get(*key))
  .cloned()
}

pub(super) fn run_status(failed: i32) -> RunStatus {
  if failed > 0 {
    RunStatus::Failed
  } else {
    RunStatus::Passed
  }
}

pub(super) fn format_timestamp(ts: Option<&prost_types::Timestamp>) -> String {
  ts.map(|timestamp| {
    #[allow(clippy::cast_sign_loss)]
    chrono::DateTime::from_timestamp(timestamp.seconds, timestamp.nanos as u32)
      .map(|datetime| datetime.to_rfc3339())
      .unwrap_or_default()
  })
  .unwrap_or_default()
}

pub(super) fn non_empty(value: &str) -> Option<String> {
  if value.is_empty() {
    None
  } else {
    Some(value.to_string())
  }
}

#[allow(clippy::needless_pass_by_value)]
pub(super) fn to_status(error: AppError) -> Status {
  match error {
    AppError::InvalidEvent(message) => Status::invalid_argument(message),
    AppError::Database(_)
    | AppError::Diesel(_)
    | AppError::DieselConnection(_)
    | AppError::Migration(_)
    | AppError::Postgres(_)
    | AppError::PostgresTls(_)
    | AppError::GrpcTransport(_)
    | AppError::Serialization(_)
    | AppError::Startup(_) => Status::internal(error.to_string()),
  }
}
