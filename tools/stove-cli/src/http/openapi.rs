use axum::Json;
use axum::Router;
use axum::routing::get;
use utoipa::OpenApi;
use utoipa::openapi::server::Server;
use utoipa_swagger_ui::{Config, SwaggerUi};

#[derive(OpenApi)]
#[openapi(
  paths(
    super::routes::get_meta,
    super::routes::get_apps,
    super::routes::post_event,
    super::routes::get_runs,
    super::routes::get_run,
    super::routes::get_tests,
    super::routes::get_entries,
    super::routes::get_raw_entries,
    super::routes::get_test_spans,
    super::routes::get_snapshots,
    super::routes::get_trace,
    super::routes::sse_handler,
    super::routes::clear_all,
    super::routes::get_test_mock_interactions,
    super::routes::get_run_mock_interactions,
    super::routes::get_unattributed_run_mock_interactions,
    super::routes::get_test_mock_warnings,
    super::routes::get_run_mock_warnings,
    super::routes::get_unattributed_run_mock_warnings,
    super::routes::get_admin_status,
    super::routes::get_database_schema,
    super::routes::execute_database_query,
    super::routes::update_retention,
    super::routes::preview_purge,
    super::routes::purge_runs
  ),
  tags(
    (name = "system", description = "Server metadata and capabilities"),
    (
      name = "ingestion",
      description = "Dashboard event ingestion using the shared stove-dashboard-api protobuf contract"
    ),
    (name = "runs", description = "Applications and test runs"),
    (name = "evidence", description = "Tests, reports, snapshots, and traces"),
    (name = "events", description = "Live dashboard updates"),
    (name = "mocks", description = "Observed mock interactions and diagnostics"),
    (name = "admin", description = "Storage administration")
  )
)]
struct ApiDoc;

/// `OpenAPI` representation of an encoded protobuf message, not a second DTO contract.
#[derive(utoipa::ToSchema)]
#[schema(value_type = String, format = Binary)]
pub(crate) struct ProtobufBody(#[allow(dead_code)] Vec<u8>);

pub(super) fn document() -> utoipa::openapi::OpenApi {
  let mut document = ApiDoc::openapi();
  document.info.title = "Stove Dashboard API".to_string();
  document.info.version = crate::STOVE_CLI_VERSION.to_string();
  // Resolve operations relative to the document so gateway path prefixes are preserved.
  document.servers = Some(vec![Server::new("..")]);
  document
}

pub(super) fn router<S>() -> Router<S>
where
  S: Clone + Send + Sync + 'static,
{
  Router::new()
    .route("/api-docs/openapi.json", get(|| async { Json(document()) }))
    .merge(SwaggerUi::new("/swagger-ui").config(Config::new(["../api-docs/openapi.json"])))
}
