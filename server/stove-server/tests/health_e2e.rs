mod common;

use common::TestServer;

#[tokio::test]
async fn health_is_available_under_the_public_url_prefix() {
  let server =
    TestServer::start_with_public_url(Some("https://stove.example/observe".into())).await;
  let response = server
    .client
    .get(format!("{}/observe/api/v1/health", server.base_url))
    .send()
    .await
    .unwrap();
  assert_eq!(response.status(), reqwest::StatusCode::OK);
  let report: serde_json::Value = response.json().await.unwrap();
  assert_eq!(report["healthy"], true);
  assert_eq!(report["backend"], "sqlite");
  assert_eq!(report["stove_server_version"], stove::STOVE_SERVER_VERSION);
  assert!(
    report["checks"]
      .as_array()
      .unwrap()
      .iter()
      .all(|check| check["healthy"] == true)
  );
  assert!(chrono::DateTime::parse_from_rfc3339(report["checked_at"].as_str().unwrap()).is_ok());
}
