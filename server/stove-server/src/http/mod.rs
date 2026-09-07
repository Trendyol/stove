mod openapi;
pub(crate) mod routes;
pub mod server;

/// The same `OpenAPI` contract served by the HTTP router and used by SPA type generation.
#[must_use]
pub fn openapi_document() -> utoipa::openapi::OpenApi {
  openapi::document()
}
