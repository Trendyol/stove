//! `Accept-Encoding` negotiation enforced before routing.
//!
//! `CompressionLayer` can only decide whether `Accept-Encoding` is satisfiable
//! once the inner service has produced a response, and rewrites that response to
//! 406 at that point (RFC 9110 §12.5.3). A mutating handler would already have
//! run and its side effect reported as a failure. Requests that no supported
//! encoding satisfies are therefore rejected here, outside the router.
//!
//! Only gzip and identity are served, so this mirrors the layer for exactly
//! those two codings: rejecting a request the layer would have served, or
//! letting through one it would have rewritten, both break parity.

use axum::extract::Request;
use axum::http::{HeaderMap, StatusCode, header};
use axum::middleware::Next;
use axum::response::{IntoResponse, Response};

/// Reject requests that accept neither gzip nor identity.
pub(crate) async fn require_satisfiable_encoding(request: Request, next: Next) -> Response {
  if is_satisfiable(request.headers()) {
    next.run(request).await
  } else {
    (
      StatusCode::NOT_ACCEPTABLE,
      [(header::VARY, "accept-encoding")],
    )
      .into_response()
  }
}

/// A complete q-value is `1.000`, so qualities are integers between 0 and 1000.
const QUALITY_ONE: u16 = 1000;

/// Every quality seen for one coding, so repeated entries keep the semantics the
/// layer gives them: the first quality wins when a wildcard fills in defaults,
/// but any positive quality makes the coding acceptable.
#[derive(Default)]
struct Coding {
  first: Option<u16>,
  positive: bool,
  rejected: bool,
}

impl Coding {
  fn observe(&mut self, quality: u16) {
    self.first.get_or_insert(quality);
    self.positive |= quality > 0;
    self.rejected |= quality == 0;
  }
}

/// Whether the request accepts gzip or identity.
///
/// Mirrors `tower_http`'s negotiation: unsupported codings are ignored, an
/// unparseable quality drops its entry, `identity` stays acceptable unless it is
/// explicitly rejected, and a wildcard provides the default quality for every
/// coding that is not listed.
fn is_satisfiable(headers: &HeaderMap) -> bool {
  let (mut gzip, mut identity) = (Coding::default(), Coding::default());
  let mut wildcard = None;
  for value in headers.get_all(header::ACCEPT_ENCODING) {
    let Ok(value) = value.to_str() else {
      continue;
    };
    for entry in value.split(',') {
      let mut parts = entry.splitn(2, ';');
      let coding = parts.next().unwrap_or_default().trim();
      let Some(quality) = parts
        .next()
        .map_or(Some(QUALITY_ONE), |parameter| quality(parameter.trim()))
      else {
        continue;
      };
      match coding.to_ascii_lowercase().as_str() {
        "gzip" | "x-gzip" => gzip.observe(quality),
        "identity" => identity.observe(quality),
        // Only the first usable wildcard counts, as in the layer.
        "*" => {
          wildcard.get_or_insert(quality);
        }
        // Codings the server cannot produce, including br, deflate, and zstd.
        _ => {}
      }
    }
  }
  match wildcard {
    Some(wildcard) => gzip.first.unwrap_or(wildcard) > 0 || identity.first.unwrap_or(wildcard) > 0,
    None => gzip.positive || identity.positive || !identity.rejected,
  }
}

/// Parse the `q=` parameter of a single `Accept-Encoding` entry.
///
/// `None` marks an unparseable quality, dropping the entry like the layer does.
fn quality(parameter: &str) -> Option<u16> {
  let value = parameter.strip_prefix(['q', 'Q'])?.strip_prefix('=')?;
  let (whole, fraction) = value.split_once('.').unwrap_or((value, ""));
  let mut quality = match whole {
    "0" => 0,
    "1" => QUALITY_ONE,
    _ => return None,
  };
  if fraction.len() > 3 || !fraction.bytes().all(|byte| byte.is_ascii_digit()) {
    return None;
  }
  let mut factor = 100;
  for digit in fraction.bytes() {
    quality += factor * u16::from(digit - b'0');
    factor /= 10;
  }
  (quality <= QUALITY_ONE).then_some(quality)
}

#[cfg(test)]
mod tests {
  use super::*;

  fn satisfiable(accept_encoding: &str) -> bool {
    let mut headers = HeaderMap::new();
    headers.insert(header::ACCEPT_ENCODING, accept_encoding.parse().unwrap());
    is_satisfiable(&headers)
  }

  #[test]
  fn missing_header_serves_identity() {
    assert!(is_satisfiable(&HeaderMap::new()));
  }

  #[test]
  fn gzip_identity_and_wildcards_are_satisfiable() {
    for header in [
      "gzip",
      "x-gzip",
      "identity",
      "*",
      "br",
      "deflate",
      "gzip, br",
      "br, gzip;q=0.5",
    ] {
      assert!(satisfiable(header), "{header}");
    }
  }

  #[test]
  fn rejected_identity_without_gzip_is_not_satisfiable() {
    for header in [
      "br, identity;q=0",
      "identity;q=0",
      "gzip;q=0, identity;q=0",
      "*;q=0",
      "br;q=1, *;q=0",
      "deflate, identity;q=0",
    ] {
      assert!(!satisfiable(header), "{header}");
    }
  }

  #[test]
  fn explicit_codings_override_a_wildcard() {
    assert!(satisfiable("*;q=0, gzip"));
    assert!(satisfiable("*;q=0, identity"));
    assert!(!satisfiable("*;q=1, gzip;q=0, identity;q=0"));
  }

  #[test]
  fn malformed_qualities_fall_back_like_the_compression_layer() {
    // An unparseable parameter drops the entry, so identity stays acceptable.
    assert!(satisfiable("identity;q=bogus"));
    assert!(satisfiable("identity;level=1"));
    assert!(satisfiable("gzip;q=2"));
    // Three fractional digits are the maximum.
    assert!(!satisfiable("identity;q=0.000, gzip;q=0.000"));
    assert!(satisfiable("identity;q=0.001"));
    // Four fractional digits are invalid, so the rejection is ignored.
    assert!(satisfiable("identity;q=0.0001, gzip;q=0"));
  }

  #[test]
  fn repeated_codings_keep_any_positive_quality() {
    assert!(satisfiable("identity;q=0, identity;q=1"));
    assert!(satisfiable("gzip;q=0, gzip"));
    // A wildcard uses the first quality for each coding.
    assert!(!satisfiable("*;q=0, identity;q=0, identity;q=1"));
    assert!(satisfiable("*;q=0, gzip"));
  }
}
