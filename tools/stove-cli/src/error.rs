use thiserror::Error;

/// Application-level error types.
///
/// Uses `thiserror` for typed, displayable errors in library-like code.
/// `anyhow` is used only at the top level (`main.rs`) for ergonomic `?` usage.
#[derive(Error, Debug)]
pub enum AppError {
  #[error("Database error: {0}")]
  Database(#[from] rusqlite::Error),

  #[error("gRPC transport error: {0}")]
  GrpcTransport(#[from] tonic::transport::Error),

  #[error("Serialization error: {0}")]
  Serialization(#[from] serde_json::Error),

  #[error("Server startup failed: {0}")]
  #[allow(dead_code)]
  Startup(String),
}

pub type Result<T> = std::result::Result<T, AppError>;

/// Convert `AppError` into an axum-compatible HTTP response.
impl axum::response::IntoResponse for AppError {
  fn into_response(self) -> axum::response::Response {
    let status = match &self {
      AppError::GrpcTransport(_) => axum::http::StatusCode::BAD_GATEWAY,
      AppError::Serialization(_) => axum::http::StatusCode::BAD_REQUEST,
      AppError::Database(_) | AppError::Startup(_) => axum::http::StatusCode::INTERNAL_SERVER_ERROR,
    };
    let body = axum::Json(serde_json::json!({ "error": self.to_string() }));
    (status, body).into_response()
  }
}
