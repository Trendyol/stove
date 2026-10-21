mod common;

use common::TestServer;
use serde_json::json;
use std::collections::BTreeMap;
use stove::storage::models::TopFilterSettings;
use stove::storage::repository::Repository;

#[tokio::test]
async fn top_filter_discovers_historical_values_and_summarizes_matching_runs() {
  let server = TestServer::start().await;
  server.repo.update_retention(0).unwrap();
  for (id, app, team, day) in [
    ("old", "shared-api", "checkout", "01"),
    ("match", "shared-api", "checkout", "02"),
    ("latest", "shared-api", "payments", "03"),
    ("second", "another-api", "checkout", "01"),
    ("empty", "empty-api", "", "01"),
    ("literal", "literal-api", "O'Reilly & / 🚀", "01"),
  ] {
    server
      .repo
      .save_run_start_with_metadata(
        id,
        app,
        &format!("2024-01-{day}T00:00:00Z"),
        None,
        &[],
        &BTreeMap::from([
          ("gitlab.team".into(), team.into()),
          ("region".into(), "eu".into()),
        ]),
      )
      .unwrap();
  }
  server.seed_run("no-metadata", "untagged-api");
  let initial = server.get_json("/top-filter").await;
  assert_eq!(initial["settings"], json!({"metadata_key": null}));
  assert_eq!(initial["available_keys"], json!(["gitlab.team", "region"]));
  assert_eq!(initial["values"], json!([]));

  let response = server
    .client
    .put(server.url("/admin/top-filter"))
    .json(&json!({"metadata_key": "gitlab.team"}))
    .send()
    .await
    .unwrap();
  assert!(response.status().is_success());
  let catalog = server.get_json("/top-filter").await;
  let values = catalog["values"].as_array().unwrap();
  assert_eq!(values.len(), 4);
  let checkout = values
    .iter()
    .find(|group| group["value"] == "checkout")
    .unwrap();
  assert_eq!(checkout["apps"][0]["app_name"], "another-api");
  assert_eq!(checkout["apps"][1]["latest_run_id"], "match");
  assert_eq!(checkout["apps"].as_array().unwrap().len(), 2);
  assert_eq!(values[0]["value"], "");

  let invalid = server
    .client
    .put(server.url("/admin/top-filter"))
    .json(&json!({"metadata_key": "  "}))
    .send()
    .await
    .unwrap();
  assert_eq!(invalid.status(), reqwest::StatusCode::BAD_REQUEST);
  assert_eq!(
    server.get_json("/top-filter").await["settings"]["metadata_key"],
    "gitlab.team"
  );

  server
    .repo
    .update_top_filter(&TopFilterSettings {
      metadata_key: Some("region".into()),
    })
    .unwrap();
  let changed = server.get_json("/top-filter").await;
  assert_eq!(changed["values"][0]["value"], "eu");
  assert_eq!(changed["values"].as_array().unwrap().len(), 1);
  server
    .repo
    .update_top_filter(&TopFilterSettings { metadata_key: None })
    .unwrap();
  assert_eq!(server.get_json("/top-filter").await["values"], json!([]));
}

#[test]
fn top_filter_setting_survives_restart_and_data_cleanup() {
  let directory = tempfile::tempdir().unwrap();
  let database = directory.path().join("dashboard.db");
  let path = database.to_str().unwrap();
  let repo = Repository::connect_sqlite(path, 0).unwrap();
  repo
    .update_top_filter(&TopFilterSettings {
      metadata_key: Some("gitlab.team".into()),
    })
    .unwrap();
  repo.clear_all().unwrap();
  drop(repo);
  let reopened = Repository::connect_sqlite(path, 0).unwrap();
  assert_eq!(
    reopened
      .top_filter_catalog()
      .unwrap()
      .settings
      .metadata_key
      .as_deref(),
    Some("gitlab.team")
  );
}
