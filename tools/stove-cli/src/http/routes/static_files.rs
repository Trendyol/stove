use axum::http::{StatusCode, Uri, header};
use axum::response::{IntoResponse, Response};
use rust_embed::Embed;

/// Embedded SPA assets, baked into the binary at compile time.
///
/// In development, this folder may be empty — the SPA is served by Vite's dev server instead.
#[derive(Embed)]
#[folder = "spa/dist/"]
#[allow(dead_code)]
struct SpaAssets;

/// Serve embedded SPA files, falling back to `index.html` for client-side routing.
pub async fn static_handler(uri: Uri) -> Response {
  let path = uri.path().trim_start_matches('/');

  // Try exact file match first
  if let Some(file) = SpaAssets::get(path) {
    let mime = mime_guess::from_path(path).first_or_octet_stream();
    return ([(header::CONTENT_TYPE, mime.as_ref())], file.data).into_response();
  }

  // Fallback to index.html for SPA client-side routing
  match SpaAssets::get("index.html") {
    Some(file) => ([(header::CONTENT_TYPE, "text/html")], file.data).into_response(),
    None => (
      StatusCode::NOT_FOUND,
      "SPA not built. Run: cd spa && npm run build",
    )
      .into_response(),
  }
}
