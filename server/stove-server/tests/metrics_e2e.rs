mod common;
use common::TestServer;

#[tokio::test]
async fn prometheus_metrics_use_public_prefix_and_scrapes_do_not_query_storage() {
  let server =
    TestServer::start_with_public_url(Some("https://stove.example/observe".into())).await;
  server.repo.get_runs(None).unwrap();
  // Breaking the application schema makes any fresh DB query fail. Scrapes
  // must still expose the counters collected before the failure.
  server
    .repo
    .execute_database_query("DROP TABLE runs", 1)
    .unwrap();
  assert!(server.repo.get_runs(None).is_err());
  let response = server
    .client
    .get(format!("{}/observe/metrics", server.base_url))
    .send()
    .await
    .unwrap();
  assert_eq!(response.status(), reqwest::StatusCode::OK);
  assert_eq!(response.headers()["content-type"], prometheus::TEXT_FORMAT);
  assert_eq!(response.headers()["cache-control"], "no-store");
  let text = response.text().await.unwrap();
  assert!(text.contains("# TYPE stove_db_duration_seconds histogram"));
  assert!(
    text
      .lines()
      .any(|line| line.starts_with("stove_db_duration_seconds_count{")
        && line.contains("kind=\"operation\",operation=\"get_runs_filtered\"")
        && line.ends_with(" 2"))
  );
  assert!(
    text
      .lines()
      .any(|line| line.starts_with("stove_db_errors_total{")
        && line.contains("kind=\"operation\",operation=\"get_runs_filtered\"")
        && line.ends_with(" 1"))
  );
  assert!(!text.contains("DROP TABLE"));
  let again = server
    .client
    .get(format!("{}/metrics", server.base_url))
    .send()
    .await
    .unwrap()
    .text()
    .await
    .unwrap();
  assert!(
    again
      .lines()
      .any(|line| line.starts_with("stove_db_duration_seconds_count{")
        && line.contains("kind=\"operation\",operation=\"get_runs_filtered\"")
        && line.ends_with(" 2"))
  );
}
