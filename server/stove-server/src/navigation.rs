//! Stable, payload-free references shared by HTTP, MCP, and the dashboard.
use serde::Serialize;
use std::fmt::Write;
use utoipa::ToSchema;

#[derive(Debug, Clone, Serialize, ToSchema)]
pub struct Navigation {
  pub run_id: String,
  #[schema(required = true)]
  pub test_id: Option<String>,
  pub path: String,
  #[schema(required = true)]
  pub url: Option<String>,
}

pub fn component(value: &str) -> String {
  let mut encoded = String::new();
  for byte in value.bytes() {
    if byte.is_ascii_alphanumeric() || b"-_.~".contains(&byte) {
      encoded.push(char::from(byte));
    } else {
      write!(encoded, "%{byte:02X}").expect("writing to a string cannot fail");
    }
  }
  encoded
}

pub fn reference(run_id: &str, test_id: Option<&str>, focus: Option<(&str, i64)>) -> Navigation {
  let mut path = format!("/runs/{}", component(run_id));
  if let Some(test_id) = test_id {
    write!(path, "/tests/{}", component(test_id)).expect("writing to a string cannot fail");
  }
  if let Some((kind, id)) = focus {
    let tab = match kind {
      "span" => "trace",
      "snapshot" => "snapshots",
      "interaction" | "warning" => "mocks",
      _ => "timeline",
    };
    write!(path, "?tab={tab}&focus={kind}:{id}").expect("writing to a string cannot fail");
  }
  Navigation {
    run_id: run_id.into(),
    test_id: test_id.map(str::to_owned),
    path,
    url: None,
  }
}

pub fn error_reference(run_id: &str, test_id: &str, error: Option<&str>) -> Option<Navigation> {
  error.filter(|error| !error.is_empty()).map(|_| {
    let mut navigation = reference(run_id, Some(test_id), None);
    navigation.path.push_str("?tab=timeline&focus=error");
    navigation
  })
}

/// A configured browser origin may include a gateway prefix. Never infer it from Host.
pub fn validate_public_url(value: &str) -> Result<String, String> {
  let url = reqwest::Url::parse(value).map_err(|_| "public_url must be an absolute HTTP(S) URL")?;
  if !matches!(url.scheme(), "http" | "https")
    || url.host_str().is_none()
    || !url.username().is_empty()
    || url.password().is_some()
    || url.query().is_some()
    || url.fragment().is_some()
  {
    return Err("public_url must be an HTTP(S) URL without credentials, query, or fragment".into());
  }
  Ok(url.as_str().trim_end_matches('/').to_string())
}

pub fn base_path(public_url: Option<&str>) -> String {
  public_url
    .and_then(|value| reqwest::Url::parse(value).ok())
    .map_or_else(String::new, |url| {
      url.path().trim_end_matches('/').to_owned()
    })
}

#[cfg(test)]
mod tests {
  use super::*;
  #[test]
  fn shared_browser_link_contract() {
    let cases: Vec<serde_json::Value> =
      serde_json::from_str(include_str!("../tests/fixtures/navigation.json")).unwrap();
    for case in cases {
      let nav = reference(
        case["run"].as_str().unwrap(),
        case["test"].as_str(),
        Some((case["kind"].as_str().unwrap(), case["id"].as_i64().unwrap())),
      );
      assert_eq!(nav.path, case["path"]);
    }
  }
  #[test]
  fn rejects_non_browser_urls_and_preserves_gateway_prefixes() {
    assert_eq!(
      validate_public_url("https://stove.example/observe/").unwrap(),
      "https://stove.example/observe"
    );
    for value in [
      "/relative",
      "javascript:alert(1)",
      "https://user:password@stove.example",
      "https://stove.example/?token=secret",
      "https://stove.example/#fragment",
    ] {
      assert!(validate_public_url(value).is_err());
    }
  }
  #[test]
  fn links_encode_identifiers_without_using_test_names() {
    let link = reference("run / +", Some("test.東京!"), Some(("entry", 42)));
    assert_eq!(
      link.path,
      "/runs/run%20%2F%20%2B/tests/test.%E6%9D%B1%E4%BA%AC%21?tab=timeline&focus=entry:42"
    );
    assert!(link.url.is_none());
  }
}
