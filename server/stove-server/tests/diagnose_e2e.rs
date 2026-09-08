//! One-call diagnosis preserves execution scope and retrieves evidence before clipping.
mod common;
use common::TestServer;
use serde_json::{Value, json};
use std::collections::BTreeMap;

async fn diagnose(server: &TestServer, args: Value) -> Value {
  tool(server, "stove_diagnose", args).await
}

async fn tool(server: &TestServer, name: &str, args: Value) -> Value {
  let response: Value = server
    .client
    .post(server.mcp_url())
    .json(&json!({
      "jsonrpc": "2.0", "id": 1, "method": "tools/call",
    "params": {"name": name, "arguments": args}
    }))
    .send()
    .await
    .unwrap()
    .json()
    .await
    .unwrap();
  let result = &response["result"];
  if let Some(content) = result.get("structuredContent") {
    assert_eq!(
      serde_json::from_str::<Value>(result["content"][0]["text"].as_str().unwrap()).unwrap(),
      *content
    );
  }
  response
}

fn seed(server: &TestServer, run: &str, job: &str) {
  server.repo.update_retention(0).unwrap();
  server
    .repo
    .save_run_start_with_metadata(
      run,
      "checkout",
      "2026-09-07T10:00:00Z",
      None,
      &[],
      &BTreeMap::from([("pipeline".into(), "42".into()), ("job".into(), job.into())]),
    )
    .unwrap();
  server.seed_test(run, "same-test-id", "places order", "OrderSpec");
  server.end_test_failed(run, "same-test-id", 100, &format!("failure in {job}"));
  server.end_run(run, 0, 1, 100);
}

#[tokio::test]
async fn exact_metadata_resolves_and_diagnoses_without_discovery_calls() {
  let server = TestServer::start().await;
  seed(&server, "run-a", "a");
  seed(&server, "run-b", "b");
  server.seed_entry_full(
    "run-a",
    "same-test-id",
    "HTTP",
    "assert total",
    "FAILED",
    "{}",
    "{}",
    "100",
    "99",
    "amount mismatch",
    "",
  );
  let response = diagnose(
    &server,
    json!({"app_name":"checkout", "metadata":{"pipeline":"42", "job":"a"}}),
  )
  .await;
  let content = &response["result"]["structuredContent"];
  assert_eq!(content["run_id"], "run-a");
  assert_eq!(
    content["diagnoses"][0]["findings"][0]["kind"],
    "assertion_mismatch"
  );
  assert_eq!(content["diagnoses"][0]["findings"][0]["expected"], 100);
  assert_eq!(content["diagnoses"][0]["findings"][0]["actual"], 99);
  assert!(!content.to_string().contains("failure in b"));
}

#[tokio::test]
async fn ambiguous_missing_and_conflicting_selectors_never_choose_another_run() {
  let server = TestServer::start().await;
  seed(&server, "run-a", "a");
  seed(&server, "run-b", "b");
  let response = diagnose(
    &server,
    json!({"app_name":"checkout", "metadata":{"pipeline":"42"}}),
  )
  .await;
  let content = &response["result"]["structuredContent"];
  assert_eq!(content["status"], "ambiguous_run");
  assert_eq!(content["matched_runs"], 2);
  assert!(content.get("diagnoses").is_none());
  for args in [
    json!({"run_id":"run-a", "metadata":{"job":"b"}}),
    json!({"run_id":"run-a", "app_name":"other"}),
    json!({"run_id":"absent"}),
  ] {
    assert_eq!(
      diagnose(&server, args).await["result"]["structuredContent"]["status"],
      "not_found"
    );
  }
  for args in [
    json!({}),
    json!({"app_name":"checkout"}),
    json!({"app_name":"checkout", "metadata":{}}),
    json!({"run_id":""}),
    json!({"run_id":"run-a", "limit":6}),
    json!({"run_id":"run-a", "test_id":"absent"}),
  ] {
    assert_eq!(diagnose(&server, args).await["result"]["isError"], true);
  }
}

#[tokio::test]
async fn wide_snapshot_diagnosis_is_discovered_without_a_supplied_pointer() {
  let server =
    TestServer::start_with_public_url(Some("https://stove.example/observe".into())).await;
  seed(&server, "run-a", "a");
  let mut catalog = serde_json::Map::new();
  for i in 0..1200 {
    catalog.insert(
      format!("sku_{i:04}"),
      json!({"description":"record".repeat(40)}),
    );
  }
  catalog.get_mut("sku_0999").unwrap()["diagnosis"] = json!("SKU-0999 unavailable in AMS");
  server.seed_snapshot(
    "run-a",
    "same-test-id",
    "Kafka",
    &json!({"catalog":catalog,
    "authorization":{"diagnosis":"hidden-secret"}})
    .to_string(),
    "state",
  );
  let response = diagnose(&server, json!({"run_id":"run-a", "budget":"tiny"})).await;
  let content = &response["result"]["structuredContent"];
  let finding = &content["diagnoses"][0]["findings"][0];
  assert_eq!(finding["kind"], "diagnostic_field");
  assert_eq!(finding["message"], "SKU-0999 unavailable in AMS");
  assert_eq!(
    finding["location"]["json_pointer"],
    "/catalog/sku_0999/diagnosis"
  );
  assert!(
    finding["navigation"]["url"]
      .as_str()
      .unwrap()
      .starts_with("https://stove.example/observe/runs/run-a/tests/same-test-id")
  );
  assert!(
    finding["navigation"]["path"]
      .as_str()
      .unwrap()
      .contains("pointer=")
  );
  assert!(!content.to_string().contains("hidden-secret"));
  assert!(content.to_string().len() < 6000);
}

#[tokio::test]
async fn exception_location_is_ranked_before_the_assertion_symptom() {
  let server = TestServer::start().await;
  seed(&server, "run-a", "a");
  server.seed_entry_full(
    "run-a",
    "same-test-id",
    "HTTP",
    "order created",
    "FAILED",
    "{}",
    "{}",
    "201",
    "500",
    "wrong status",
    "trace-a",
  );
  server.seed_span_with_exception(
    "run-a",
    "trace-a",
    "span-a",
    "",
    "OrderRepository.insert",
    "checkout",
    "ERROR",
    "DuplicateKeyException",
    "SQLSTATE 23505 orders_pkey",
    "[\"OrderRepository.kt:72\",\"OrderService.kt:38\"]",
  );
  let response = diagnose(&server, json!({"run_id":"run-a", "test_id":"same-test-id"})).await;
  let findings = &response["result"]["structuredContent"]["diagnoses"][0]["findings"];
  assert_eq!(findings[0]["kind"], "exception");
  assert_eq!(
    findings[0]["location"]["operation"],
    "OrderRepository.insert"
  );
  assert_eq!(findings[0]["stack_excerpt"][0], "OrderRepository.kt:72");
  assert_eq!(findings[1]["kind"], "assertion_mismatch");
  for finding in findings.as_array().unwrap() {
    if let Some(call) = finding.get("raw_tool_call") {
      assert_eq!(call["arguments"]["run_id"], "run-a");
      let raw = tool(
        &server,
        call["tool"].as_str().unwrap(),
        call["arguments"].clone(),
      )
      .await;
      assert_eq!(raw["result"]["isError"], false, "{raw}");
    }
  }
}

#[tokio::test]
async fn agent_can_page_every_failure_without_switching_to_a_newer_run() {
  let server = TestServer::start().await;
  seed(&server, "run-a", "a");
  for i in 0..4 {
    let id = format!("failure-{i}");
    server.seed_test("run-a", &id, "failure", "Spec");
    server.end_test_failed("run-a", &id, 100, "failure");
  }
  let mut args = json!({"app_name":"checkout", "metadata":{"job":"a"}, "limit":2});
  let mut seen = std::collections::BTreeSet::new();
  for page in 0..3 {
    let response = diagnose(&server, args).await;
    let content = &response["result"]["structuredContent"];
    assert_eq!(content["run_id"], "run-a");
    for test in content["diagnoses"].as_array().unwrap() {
      assert!(
        seen.insert(test["test"]["test_id"].as_str().unwrap().to_string()),
        "test repeated"
      );
    }
    if page == 2 {
      assert!(content["next_tool_call"].is_null());
      assert_eq!(content["omitted_tests"], 0);
    } else {
      args = content["next_tool_call"]["arguments"].clone();
      assert_eq!(args["run_id"], "run-a");
      assert_eq!(args["metadata"], json!({"job":"a"}));
      if page == 0 {
        seed(&server, "run-new", "new-job");
      }
      continue;
    }
    break;
  }
  assert_eq!(seen.len(), 5);
  for args in [
    json!({"run_id":"run-a", "after_test_id":"unknown"}),
    json!({"app_name":"checkout", "metadata":{"job":"a"}, "after_test_id":"same-test-id"}),
    json!({"run_id":"run-a", "test_id":"same-test-id", "after_test_id":"same-test-id"}),
  ] {
    assert_eq!(diagnose(&server, args).await["result"]["isError"], true);
  }
}

#[tokio::test]
async fn incomplete_runs_and_missing_evidence_are_explicit() {
  let server = TestServer::start().await;
  seed(&server, "run-a", "a");
  for i in 0..4 {
    let id = format!("test-{i}");
    server.seed_test("run-a", &id, "other failure", "OrderSpec");
    server.end_test_failed("run-a", &id, 100, "something failed");
  }
  let response = diagnose(&server, json!({"run_id":"run-a", "limit":2})).await;
  let content = &response["result"]["structuredContent"];
  assert_eq!(content["status"], "partial");
  assert_eq!(content["omitted_tests"], 3);
  assert_eq!(content["diagnoses"][0]["status"], "insufficient_evidence");
  assert_eq!(
    content["diagnoses"][0]["timeline_summary"]["total_events"],
    0
  );
  assert_eq!(
    content["diagnoses"][0]["trace_summary"]["trace_status"],
    "uncorrelated"
  );
  server.seed_run("live", "live");
  assert_eq!(
    diagnose(&server, json!({"run_id":"live"})).await["result"]["structuredContent"]["data_freshness"],
    "partial"
  );
  server.seed_test("live", "passed", "passed", "Spec");
  server.end_test("live", "passed", 100);
  server.end_run("live", 1, 0, 100);
  assert_eq!(
    diagnose(&server, json!({"run_id":"live", "test_id":"passed"})).await["result"]["structuredContent"]
      ["status"],
    "no_failures"
  );
}

#[tokio::test]
async fn diagnosis_includes_bounded_scoped_timeline_and_failure_path() {
  use stove::storage::models::NewEntry;
  let server =
    TestServer::start_with_public_url(Some("https://stove.example/observe".into())).await;
  seed(&server, "run-a", "a");
  seed(&server, "run-b", "b");
  // Insert out of order: the returned context must follow event time.
  for i in (0..20).rev() {
    server
      .repo
      .save_entry(&NewEntry {
        run_id: "run-a".into(),
        test_id: "same-test-id".into(),
        timestamp: format!("2026-09-07T10:00:{i:02}Z"),
        system: "HTTP".into(),
        action: format!("step-{i}"),
        result: if i == 17 { "FAILED" } else { "PASSED" }.into(),
        trace_id: "failed-trace".into(),
        input: String::new(),
        output: String::new(),
        metadata: "{}".into(),
        expected: String::new(),
        actual: String::new(),
        error: String::new(),
        assertion_id: format!("step-{i}"),
        correlation_key: String::new(),
      })
      .unwrap();
  }
  for i in 0..40 {
    let id = format!("span-{i}");
    let parent = if i == 0 {
      String::new()
    } else {
      format!("span-{}", i - 1)
    };
    if i == 39 {
      server.seed_span_with_exception(
        "run-a",
        "failed-trace",
        &id,
        &parent,
        "reserve",
        "inventory",
        "ERROR",
        "Timeout",
        "reservation deadline",
        "[]",
      );
    } else {
      server.seed_span("run-a", "failed-trace", &id, &parent, "request", "checkout");
    }
  }
  // A larger healthy trace in the same test must not displace its failed trace.
  server.seed_entry_full(
    "run-a",
    "same-test-id",
    "HTTP",
    "health",
    "PASSED",
    "",
    "",
    "",
    "",
    "",
    "healthy-trace",
  );
  for i in 0..100 {
    server.seed_span(
      "run-a",
      "healthy-trace",
      &format!("healthy-{i}"),
      "",
      "health",
      "checkout",
    );
  }
  server.seed_entry_full(
    "run-b",
    "same-test-id",
    "HTTP",
    "other-run-secret",
    "FAILED",
    "",
    "",
    "",
    "",
    "",
    "failed-trace",
  );
  server.seed_span(
    "run-b",
    "failed-trace",
    "span-39",
    "",
    "other-run-secret",
    "other",
  );
  server.seed_test("run-a", "other-test", "other", "Spec");
  server.seed_entry_full(
    "run-a",
    "other-test",
    "HTTP",
    "other-test-secret",
    "FAILED",
    "",
    "",
    "",
    "",
    "",
    "other-trace",
  );
  server.seed_span(
    "run-a",
    "other-trace",
    "other-span",
    "",
    "other-test-secret",
    "other",
  );

  for budget in ["tiny", "compact", "full"] {
    let response = diagnose(
      &server,
      json!({"run_id":"run-a", "test_id":"same-test-id", "budget":budget}),
    )
    .await;
    let content = &response["result"]["structuredContent"];
    let diagnosis = &content["diagnoses"][0];
    assert_eq!(response["result"]["isError"], false, "{response}");
    let timeline = &diagnosis["timeline_summary"];
    let events = timeline["events"].as_array().unwrap();
    assert_eq!(events.len(), 5);
    assert_eq!(timeline["total_events"], 21);
    assert_eq!(timeline["omitted_events"], 16);
    assert_eq!(
      events
        .iter()
        .map(|e| e["action"].as_str().unwrap())
        .collect::<Vec<_>>(),
      vec!["step-15", "step-16", "step-17", "step-18", "step-19"]
    );
    let trace = &diagnosis["trace_summary"];
    let path = trace["critical_path"].as_array().unwrap();
    assert_eq!(trace["trace_ids"], json!(["failed-trace"]));
    assert_eq!(trace["omitted_traces"], 1);
    assert_eq!(trace["total_spans"], 140);
    assert_eq!(trace["omitted_spans"], 132);
    assert_eq!(path.len(), 8);
    assert_eq!(path[0]["span_id"], "span-32");
    assert_eq!(path[7]["span_id"], "span-39");
    assert_eq!(path[7]["status"], "ERROR");
    assert!(path[7].get("exception_stack_trace").is_none());
    assert!(path[7]["start_time_nanos"].is_number());
    assert!(path[7]["end_time_nanos"].is_number());
    for item in events.iter().chain(path) {
      assert!(
        item["navigation"]["url"]
          .as_str()
          .unwrap()
          .starts_with("https://stove.example/observe/runs/run-a/tests/same-test-id?")
      );
    }
    assert!(!content.to_string().contains("other-run-secret"));
    assert!(!content.to_string().contains("other-test-secret"));
    for call in [&timeline["timeline_tool_call"], &trace["trace_tool_call"]] {
      assert_eq!(call["arguments"]["run_id"], "run-a");
      assert_eq!(call["arguments"]["test_id"], "same-test-id");
      let expanded = tool(
        &server,
        call["tool"].as_str().unwrap(),
        call["arguments"].clone(),
      )
      .await;
      assert_eq!(expanded["result"]["isError"], false);
    }
  }
}
