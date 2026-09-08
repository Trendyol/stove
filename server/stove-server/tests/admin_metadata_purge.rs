mod common;
use common::TestServer;
use reqwest::StatusCode;
use serde_json::{Value, json};
use std::collections::BTreeMap;

#[tokio::test]
async fn metadata_purge_scopes_exact_values_and_preserves_unpreviewed_runs() {
  let server = TestServer::start().await;
  server.repo.update_retention(0).unwrap();
  for (id, app, branch, pipeline, started_at, active) in [
    (
      "match-a",
      "checkout",
      "main",
      "42",
      "2024-01-01T00:00:00Z",
      false,
    ),
    (
      "match-b",
      "checkout",
      "release",
      "42",
      "2024-01-01T00:00:00Z",
      false,
    ),
    (
      "other-app",
      "catalog",
      "main",
      "42",
      "2024-01-01T00:00:00Z",
      false,
    ),
    (
      "other-value",
      "checkout",
      "main-old",
      "42",
      "2024-01-01T00:00:00Z",
      false,
    ),
    (
      "other-field",
      "checkout",
      "main",
      "43",
      "2024-01-01T00:00:00Z",
      false,
    ),
    (
      "newer",
      "checkout",
      "main",
      "42",
      "2024-06-01T00:00:00Z",
      false,
    ),
    (
      "active",
      "checkout",
      "main",
      "42",
      "2024-01-01T00:00:00Z",
      true,
    ),
  ] {
    server
      .repo
      .save_run_start_with_metadata(
        id,
        app,
        started_at,
        None,
        &[],
        &BTreeMap::from([
          ("branch".into(), branch.into()),
          ("ci.pipeline".into(), pipeline.into()),
        ]),
      )
      .unwrap();
    if !active {
      server.end_run(id, 0, 0, 10);
    }
  }
  server.seed_run_at("missing-field", "checkout", "2024-01-01T00:00:00Z", &[]);
  server.end_run("missing-field", 0, 0, 10);
  server.seed_test("match-a", "test-a", "test", "Spec");
  server.seed_entry("match-a", "test-a", "HTTP", "GET /", "PASSED");
  let criteria = json!({
    "app_name": "checkout", "older_than": "2024-05-01T00:00:00Z",
    "metadata": { "branch": ["main", "release"], "ci.pipeline": ["42"] }
  });
  let preview = post_preview(&server, &criteria).await;
  assert_eq!(preview["run_ids"], json!(["match-a", "match-b"]));
  assert_eq!(preview["evidence"]["tests"], 1);
  assert_eq!(preview["evidence"]["entries"], 1);
  let mut including_active = criteria.clone();
  including_active["include_running"] = true.into();
  assert_eq!(
    post_preview(&server, &including_active).await["run_ids"],
    json!(["active", "match-a", "match-b"])
  );
  for metadata in [json!({"branch": ["unknown"]}), json!({"absent": [""]})] {
    let mut unmatched = criteria.clone();
    unmatched["metadata"] = metadata;
    assert_eq!(post_preview(&server, &unmatched).await["run_count"], 0);
  }
  for invalid in [
    json!({ "metadata": { "branch": ["main"] } }),
    json!({ "app_name": " ", "metadata": { "branch": ["main"] } }),
    json!({ "app_name": "checkout", "metadata": { "branch": [] } }),
  ] {
    let response = server
      .client
      .post(server.url("/admin/purge/preview"))
      .json(&invalid)
      .send()
      .await
      .unwrap();
    assert_eq!(response.status(), StatusCode::BAD_REQUEST);
  }
  server
    .repo
    .save_run_start_with_metadata(
      "arrived-later",
      "checkout",
      "2024-01-01T00:00:00Z",
      None,
      &[],
      &BTreeMap::from([
        ("branch".into(), "main".into()),
        ("ci.pipeline".into(), "42".into()),
      ]),
    )
    .unwrap();
  server.end_run("arrived-later", 0, 0, 10);
  let purged = server
    .client
    .post(server.url("/admin/purge"))
    .json(&json!({ "run_ids": preview["run_ids"] }))
    .send()
    .await
    .unwrap()
    .error_for_status()
    .unwrap()
    .json::<Value>()
    .await
    .unwrap();
  assert_eq!(purged["purged_runs"], 2);
  assert_eq!(server.repo.storage_stats().unwrap().runs, 7);
  for id in [
    "other-app",
    "other-value",
    "other-field",
    "newer",
    "active",
    "missing-field",
    "arrived-later",
  ] {
    assert!(
      server.repo.get_run(id).unwrap().is_some(),
      "{id} must survive"
    );
  }
  assert_eq!(server.repo.storage_stats().unwrap().evidence.entries, 0);
}

async fn post_preview(server: &TestServer, criteria: &Value) -> Value {
  server
    .client
    .post(server.url("/admin/purge/preview"))
    .json(criteria)
    .send()
    .await
    .unwrap()
    .error_for_status()
    .unwrap()
    .json::<Value>()
    .await
    .unwrap()
}
