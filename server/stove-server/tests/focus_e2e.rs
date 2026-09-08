mod common;
use common::TestServer;
use serde_json::{Value, json};

#[tokio::test]
async fn citations_retrieve_exact_retries_with_bounded_context_and_enforce_scope() {
  let server = TestServer::start().await;
  server.seed_run("run-1", "checkout");
  server.seed_test("run-1", "test-1", "same name", "Spec");
  server.seed_test("run-1", "test-2", "same name", "Spec");
  for _ in 0..25 {
    server.seed_entry("run-1", "test-1", "HTTP", "retry", "FAILED");
  }
  server.seed_entry("run-1", "test-1", "HTTP", "retry", "PASSED");
  let raw = server.repo.get_raw_entries("run-1", "test-1").unwrap();
  assert_eq!(server.repo.get_entries("run-1", "test-1").unwrap().len(), 1);
  let id = raw[12].id;
  let path = format!("/runs/run-1/tests/test-1/evidence/entry/{id}");
  let result = server.get_json(&format!("{path}?context=1")).await;
  assert_eq!(result["target"]["value"]["id"], id, "{result}");
  assert_eq!(result["target"]["value"]["result"], "FAILED");
  assert_eq!(
    result["entries"]
      .as_array()
      .unwrap()
      .iter()
      .map(|item| item["id"].as_i64().unwrap())
      .collect::<Vec<_>>(),
    raw[11..14].iter().map(|item| item.id).collect::<Vec<_>>()
  );
  assert_eq!(result["has_more_before"], true);
  assert_eq!(result["has_more_after"], true);
  let expanded = server.get_json(&format!("{path}?context=10000")).await;
  assert_eq!(expanded["context_limit"], 100);
  assert_eq!(
    server
      .get(&format!("/runs/run-1/tests/test-2/evidence/entry/{id}"))
      .await
      .status(),
    404
  );
  assert_eq!(server.get("/runs/run-1/tests/missing").await.status(), 404);
  assert_eq!(
    server
      .get("/runs/run-1/tests/test-1/evidence/invalid/1")
      .await
      .status(),
    400
  );
}

#[tokio::test]
async fn all_evidence_kinds_keep_their_actual_scope() {
  let server = TestServer::start().await;
  server.seed_full_run();
  let spans = server.repo.get_spans_for_test("run-1", "test-1").unwrap();
  assert!(!spans.is_empty());
  for span in &spans {
    let focused = server
      .get_json(&format!(
        "/runs/run-1/tests/test-1/evidence/span/{}",
        span.id
      ))
      .await;
    assert_eq!(
      focused["target"]["value"]["span_id"], span.span_id,
      "{focused}"
    );
  }
  let snapshot = &server.repo.get_snapshots("run-1", "test-1").unwrap()[0];
  let focused = server
    .get_json(&format!(
      "/runs/run-1/tests/test-1/evidence/snapshot/{}",
      snapshot.id
    ))
    .await;
  assert_eq!(
    focused["target"]["value"]["state_json"],
    snapshot.state_json
  );
  assert_eq!(
    server
      .get(&format!(
        "/runs/run-1/tests/test-2/evidence/snapshot/{}",
        snapshot.id
      ))
      .await
      .status(),
    404
  );
  server.seed_mock_interaction("run-1", None, "/ambient", false, "404", "UNATTRIBUTED", &[]);
  server.seed_mock_warning("run-1", None, "UNMATCHED", "ambient warning");
  let interaction = &server
    .repo
    .get_unattributed_mock_interactions_for_run("run-1")
    .unwrap()[0];
  let warning = &server
    .repo
    .get_unattributed_mock_warnings_for_run("run-1")
    .unwrap()[0];
  for (kind, id) in [("interaction", interaction.id), ("warning", warning.id)] {
    assert_eq!(
      server
        .get(&format!("/runs/run-1/tests/test-1/evidence/{kind}/{id}"))
        .await
        .status(),
      404
    );
    let result = server
      .get_json(&format!("/runs/run-1/evidence/{kind}/{id}"))
      .await;
    assert_eq!(result["target"]["value"]["id"], id);
    assert!(result["target"]["value"]["test_id"].is_null());
  }
}

#[tokio::test]
async fn mcp_returns_public_citations_that_resolve_through_the_gateway_prefix() {
  let server =
    TestServer::start_with_public_url(Some("https://stove.example/observe".into())).await;
  server.seed_run("run.branch", "checkout");
  server.seed_test("run.branch", "test / + 東京", "fails", "Spec");
  server.seed_entry("run.branch", "test / + 東京", "HTTP", "assert", "FAILED");
  server.end_test_failed("run.branch", "test / + 東京", 1, "wrong response");
  let response: Value = server.client.post(server.mcp_url()).json(&json!({"jsonrpc":"2.0", "id":1,"method":"tools/call","params":{"name":"stove_failure_detail","arguments":{"run_id":"run.branch","test_id":"test / + 東京","budget":"compact"}}})).send().await.unwrap().json().await.unwrap();
  let packet = &response["result"]["structuredContent"];
  let navigation = &packet["failed_entries"][0]["navigation"];
  let path = navigation["path"].as_str().unwrap();
  assert!(path.starts_with("/observe/runs/run.branch/tests/test%20%2F%20%2B%20%E6%9D%B1%E4%BA%AC?tab=timeline&focus=entry:"), "{packet}");
  assert_eq!(navigation["url"], format!("https://stove.example{path}"));
  let browser_response = server
    .client
    .get(format!("{}{path}", server.base_url))
    .send()
    .await
    .unwrap();
  assert_eq!(browser_response.status(), 200);
  let html = browser_response.text().await.unwrap();
  assert!(html.contains("name=\"stove-base\" content=\"/observe\""));
  let id = packet["failed_entries"][0]["id"].as_i64().unwrap();
  let url = format!(
    "{}/observe/api/v1/runs/run.branch/tests/test%20%2F%20%2B%20%E6%9D%B1%E4%BA%AC/evidence/entry/{id}",
    server.base_url
  );
  let result: Value = server
    .client
    .get(url)
    .send()
    .await
    .unwrap()
    .json()
    .await
    .unwrap();
  assert_eq!(result["target"]["value"]["id"], id, "{result}");
  let meta = server.get_json("/meta").await;
  assert_eq!(meta["mcp"]["endpoint"], "https://stove.example/observe/mcp");
  assert!(
    response["result"]["content"][0]["text"]
      .as_str()
      .unwrap()
      .contains("https://stove.example/observe/runs/")
  );
}

#[tokio::test]
async fn missing_citations_do_not_resolve_to_a_newer_run() {
  let server = TestServer::start().await;
  server.seed_full_run();
  let raw = server.repo.get_raw_entries("run-1", "test-1").unwrap();
  let path = format!("/runs/run-1/tests/test-1/evidence/entry/{}", raw[0].id);
  server.repo.clear_all().unwrap();
  server.seed_run("new-run", "product-api");
  server.seed_test("new-run", "test-1", "same test", "Spec");
  assert_eq!(server.get(&path).await.status(), 404);
}

async fn tool(server: &TestServer, name: &str, args: Value) -> Value {
  let response: Value = server.client.post(server.mcp_url()).json(&json!({"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":name,"arguments":args}})).send().await.unwrap().json().await.unwrap();
  assert!(response["result"]["isError"] != true, "{response}");
  response["result"]["structuredContent"].clone()
}

#[tokio::test]
async fn all_mcp_evidence_citations_resolve_and_preserve_snapshot_pointers() {
  let server =
    TestServer::start_with_public_url(Some("https://stove.example/observe".into())).await;
  server.seed_full_run();
  server.seed_mock_interaction(
    "run-1",
    Some("test-1"),
    "/owned",
    false,
    "404",
    "PROVEN_HEADER",
    &[],
  );
  server.seed_mock_interaction("run-1", None, "/ambient", false, "404", "UNATTRIBUTED", &[]);
  server.seed_mock_warning("run-1", Some("test-1"), "UNMATCHED", "warning");
  server.seed_mock_warning("run-1", None, "UNMATCHED", "ambient warning");
  for (name, args) in [
    ("stove_failures", json!({"run_id":"run-1"})),
    (
      "stove_failure_detail",
      json!({"run_id":"run-1","test_id":"test-1"}),
    ),
    (
      "stove_timeline",
      json!({"run_id":"run-1","test_id":"test-1"}),
    ),
    ("stove_trace", json!({"run_id":"run-1","test_id":"test-1"})),
    (
      "stove_trace",
      json!({"run_id":"run-1","test_id":"test-1","view":"tree"}),
    ),
    (
      "stove_trace",
      json!({"run_id":"run-1","test_id":"test-1","view":"exceptions"}),
    ),
    (
      "stove_snapshot",
      json!({"run_id":"run-1","test_id":"test-1","json_pointer":""}),
    ),
    ("stove_interactions", json!({"run_id":"run-1"})),
  ] {
    let result = tool(&server, name, args).await;
    let mut citations = Vec::new();
    collect_citations(&result, &mut citations);
    assert!(!citations.is_empty(), "no citations from {name}: {result}");
    for nav in citations {
      let path = nav["path"].as_str().unwrap();
      assert!(path.starts_with("/observe/runs/"), "{name}: {nav}");
      assert_eq!(nav["url"], format!("https://stove.example{path}"));
      if let Some((kind, id)) = path
        .split("focus=")
        .nth(1)
        .and_then(|focus| focus.split('&').next())
        .and_then(|focus| focus.split_once(':'))
      {
        let id: i64 = id.parse().unwrap();
        let test_path = nav["test_id"]
          .as_str()
          .map(|test| format!("/tests/{test}"))
          .unwrap_or_default();
        let focused = server
          .get_json(&format!("/runs/run-1{test_path}/evidence/{kind}/{id}"))
          .await;
        assert_eq!(focused["target"]["value"]["id"], id, "{name}: {focused}");
        let raw = tool(
          &server,
          "stove_raw_evidence",
          json!({"run_id":"run-1","test_id":nav["test_id"],"kind":kind,"id":id}),
        )
        .await;
        assert!(
          raw["raw_evidence"]["evidence"]["navigation"]["url"]
            .as_str()
            .unwrap()
            .starts_with("https://stove.example/observe/")
        );
      }
    }
    if name == "stove_snapshot" {
      assert!(
        result["snapshots"][0]["navigation"]["path"]
          .as_str()
          .unwrap()
          .ends_with("&pointer=")
      );
    }
  }
}

fn collect_citations<'a>(value: &'a Value, found: &mut Vec<&'a Value>) {
  match value {
    Value::Array(items) => {
      for item in items {
        collect_citations(item, found);
      }
    }
    Value::Object(object) => {
      for (key, value) in object {
        if (key == "navigation" || key == "error_navigation") && value.is_object() {
          found.push(value);
        } else {
          collect_citations(value, found);
        }
      }
    }
    _ => {}
  }
}

#[tokio::test]
async fn span_context_is_bounded_cycle_safe_and_scoped_to_one_trace() {
  let server = TestServer::start().await;
  server.seed_run("run-1", "checkout");
  server.seed_run("run-2", "checkout");
  for index in 0..6 {
    server.seed_span(
      "run-1",
      "trace",
      &format!("span-{index}"),
      &if index == 0 {
        String::new()
      } else {
        format!("span-{}", index - 1)
      },
      "operation",
      "service",
    );
  }
  server.seed_span("run-2", "trace", "span-4", "", "wrong run", "service");
  server.seed_span(
    "run-1",
    "other-trace",
    "span-4",
    "",
    "wrong trace",
    "service",
  );
  let target = server
    .repo
    .get_trace("trace")
    .unwrap()
    .into_iter()
    .find(|span| span.span_id == "span-5")
    .unwrap();
  let path = format!("/runs/run-1/evidence/span/{}", target.id);
  let result = server.get_json(&format!("{path}?context=2")).await;
  assert_eq!(result["has_more_before"], true);
  assert_eq!(result["spans"].as_array().unwrap().len(), 3);
  assert_eq!(result["spans"][0]["span_id"], "span-3");
  assert_eq!(result["spans"][2]["id"], target.id);
  let result = server.get_json(&format!("{path}?context=100")).await;
  assert_eq!(result["spans"].as_array().unwrap().len(), 6);
  assert_eq!(result["has_more_before"], false);
  server.seed_span("run-1", "cycle", "a", "b", "cycle", "service");
  server.seed_span("run-1", "cycle", "b", "a", "cycle", "service");
  let id = server.repo.get_trace("cycle").unwrap()[0].id;
  let result = server
    .get_json(&format!("/runs/run-1/evidence/span/{id}?context=100"))
    .await;
  assert_eq!(result["spans"].as_array().unwrap().len(), 2);
  assert_eq!(result["has_more_before"], false);
  let raw = tool(
    &server,
    "stove_raw_evidence",
    json!({"trace_id":"cycle","kind":"span","id":id}),
  )
  .await;
  assert_eq!(
    raw["raw_evidence"]["evidence"]["navigation"]["test_id"],
    Value::Null
  );
  assert!(
    server
      .repo
      .get_trace_span("other-trace", id)
      .unwrap()
      .is_none()
  );
}
