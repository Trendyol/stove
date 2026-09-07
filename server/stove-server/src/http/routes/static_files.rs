use crate::http::server::AppState;
use axum::extract::State;
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
pub async fn static_handler(State(state): State<AppState>, uri: Uri) -> Response {
  let path = uri.path().trim_start_matches('/');

  // Try exact file match first
  if let Some(file) = SpaAssets::get(path).filter(|_| path != "index.html") {
    let mime = mime_guess::from_path(path).first_or_octet_stream();
    return ([(header::CONTENT_TYPE, mime.as_ref())], file.data).into_response();
  }

  if path != "index.html" && is_asset_like_path(path) && !path.starts_with("runs/") {
    return (StatusCode::NOT_FOUND, "Asset not found").into_response();
  }

  // Fallback to index.html for SPA client-side routing
  match SpaAssets::get("index.html") {
    Some(file) => {
      let base = crate::navigation::base_path(state.public_url.as_deref());
      let base = base
        .replace('&', "&amp;")
        .replace('\"', "&quot;")
        .replace('<', "&lt;")
        .replace('>', "&gt;");
      let html = String::from_utf8_lossy(&file.data)
        .replace(
          "<head>",
          &format!("<head><meta name=\"stove-base\" content=\"{base}\">"),
        )
        .replace("\"/assets/", &format!("\"{base}/assets/"))
        .replace("\"./assets/", &format!("\"{base}/assets/"));
      ([(header::CONTENT_TYPE, "text/html")], html).into_response()
    }
    None => (
      StatusCode::NOT_FOUND,
      "SPA not built. Run: cd spa && npm run build",
    )
      .into_response(),
  }
}

fn is_asset_like_path(path: &str) -> bool {
  !path.is_empty() && std::path::Path::new(path).extension().is_some()
}

#[cfg(test)]
mod tests {
  use super::is_asset_like_path;

  #[test]
  fn detects_asset_like_paths_by_extension() {
    assert!(is_asset_like_path("assets/app.js"));
    assert!(is_asset_like_path("styles/main.css"));
    assert!(!is_asset_like_path(""));
    assert!(!is_asset_like_path("runs/run-1"));
    assert!(!is_asset_like_path("dashboard/settings"));
  }
}
