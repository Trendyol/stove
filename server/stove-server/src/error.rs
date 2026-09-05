use thiserror::Error;

/// Application-level error types.
///
/// Uses `thiserror` for typed, displayable errors in library-like code.
/// `anyhow` is used only at the top level (`main.rs`) for ergonomic `?` usage.
#[derive(Error, Debug)]
pub enum AppError {
  #[error("Server capacity exhausted; retry the request without changing event identities")]
  Overloaded,

  #[error("Database error: {0}")]
  Database(#[from] rusqlite::Error),

  #[error("ORM error: {0}")]
  Diesel(#[from] diesel::result::Error),

  #[error("Database connection error: {0}")]
  DieselConnection(#[from] diesel::ConnectionError),

  #[error("Migration error: {0}")]
  Migration(#[from] refinery::Error),

  #[error("PostgreSQL error: {0}")]
  Postgres(#[from] postgres::Error),

  #[error("PostgreSQL TLS error: {0}")]
  PostgresTls(#[from] native_tls::Error),

  #[error("gRPC transport error: {0}")]
  GrpcTransport(#[from] tonic::transport::Error),

  #[error("Serialization error: {0}")]
  Serialization(#[from] serde_json::Error),

  #[error("Invalid dashboard event: {0}")]
  InvalidEvent(String),

  #[error("Server startup failed: {0}")]
  Startup(String),
}

pub type Result<T> = std::result::Result<T, AppError>;

/// Convert `AppError` into an axum-compatible HTTP response.
impl axum::response::IntoResponse for AppError {
  fn into_response(self) -> axum::response::Response {
    let status = match &self {
      AppError::Overloaded => axum::http::StatusCode::SERVICE_UNAVAILABLE,
      AppError::GrpcTransport(_) => axum::http::StatusCode::BAD_GATEWAY,
      AppError::Serialization(_) | AppError::InvalidEvent(_) => axum::http::StatusCode::BAD_REQUEST,
      AppError::Database(_)
      | AppError::Diesel(_)
      | AppError::DieselConnection(_)
      | AppError::Migration(_)
      | AppError::Postgres(_)
      | AppError::PostgresTls(_)
      | AppError::Startup(_) => axum::http::StatusCode::INTERNAL_SERVER_ERROR,
    };
    let body = axum::Json(serde_json::json!({ "error": self.to_string() }));
    (status, body).into_response()
  }
}
