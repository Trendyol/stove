//! End-to-end tests for the Stove MCP endpoint.

mod common;

use common::TestServer;
use reqwest::StatusCode;
use serde_json::{Value, json};
use stove::grpc::service::DashboardEventServiceImpl;
use stove::proto;
use stove::proto::dashboard_event_service_server::DashboardEventService;
use tonic::Request;

async fn mcp_call(server: &TestServer, method: &str, params: Value) -> Value {
  server
    .client
    .post(server.mcp_url())
    .json(&json!({
      "jsonrpc": "2.0",
      "id": 1,
      "method": method,
      "params": params,
    }))
    .send()
    .await
    .expect("MCP request should succeed")
    .json::<Value>()
    .await
    .expect("MCP response should be valid JSON")
}

async fn mcp_tool(server: &TestServer, name: &str, arguments: Value) -> Value {
  mcp_call(
    server,
    "tools/call",
    json!({
      "name": name,
      "arguments": arguments,
    }),
  )
  .await
}

#[tokio::test]
async fn mcp_lists_tools_and_initializes() {
  let server = TestServer::start().await;

  let initialized = mcp_call(
    &server,
    "initialize",
    json!({
      "protocolVersion": "2025-06-18",
      "capabilities": {},
      "clientInfo": { "name": "test", "version": "1" }
    }),
  )
  .await;
  let tools = mcp_call(&server, "tools/list", json!({})).await;

  assert_eq!(initialized["result"]["serverInfo"]["name"], "stove");
  assert_eq!(tools["result"]["tools"][0]["name"], "stove_apps");
  assert!(
    tools["result"]["tools"]
      .as_array()
      .unwrap()
      .iter()
      .any(|tool| tool["name"] == "stove_failure_detail")
  );
}

#[tokio::test]
async fn failures_are_grouped_by_app_and_run_with_exact_detail_calls() {
  let server = TestServer::start().await;
  seed_multi_app_failures(&server);

  let response = mcp_tool(&server, "stove_failures", json!({ "limit": 10 })).await;
  let groups = response["result"]["structuredContent"]["groups"]
    .as_array()
    .unwrap();

  assert_eq!(groups.len(), 3);
  assert_eq!(groups[0]["app_name"], "checkout-api");
  assert_eq!(groups[0]["failures"][0]["run_id"], "run-checkout-2");
  assert_eq!(
    groups[0]["failures"][0]["detail_tool_call"]["arguments"]["test_id"],
    "duplicate-name"
  );
  assert_eq!(groups[1]["app_name"], "catalog-api");
  assert_eq!(groups[2]["app_name"], "checkout-api");
}

#[tokio::test]
async fn failure_detail_includes_timeline_trace_and_snapshot_summaries() {
  let server = TestServer::start().await;
  server.seed_run_at(
    "run-1",
    "checkout-api",
    "2024-06-01T10:00:00Z",
    &["HTTP", "Kafka"],
  );
  server.seed_test("run-1", "test-1", "declines expired card", "CheckoutSpec");
  server.seed_entry_full(
    "run-1",
    "test-1",
    "HTTP",
    "POST /checkout",
    "PASSED",
    r#"{"card":"expired","authorization":"secret"}"#,
    r#"{"status":"PENDING"}"#,
    "",
    "",
    "",
    "trace-1",
  );
  server.seed_entry_full(
    "run-1",
    "test-1",
    "Kafka",
    "should publish OrderRejected",
    "FAILED",
    r#"{"authorization":"secret"}"#,
    "",
    "OrderRejected",
    "nothing",
    "Expected rejection event",
    "trace-1",
  );
  server.seed_span_timed(
    "run-1",
    "trace-1",
    "span-1",
    "",
    "POST /checkout",
    "checkout-api",
    1_000,
    2_000,
    r#"{"x-stove-test-id":"test-1"}"#,
  );
  server.seed_span_with_exception(
    "run-1",
    "trace-1",
    "span-2",
    "span-1",
    "PaymentClient.authorize",
    "checkout-api",
    "ERROR",
    "PaymentDeclinedException",
    "expired card",
    "stack line 1\nstack line 2",
  );
  server.seed_snapshot(
    "run-1",
    "test-1",
    "Kafka",
    r#"{"published":[],"failed":[{"topic":"orders","token":"secret"}]}"#,
    "Published: 0\nFailed: 1",
  );
  server.end_test_failed("run-1", "test-1", 800, "Expected rejection event");
  server.end_run("run-1", 0, 1, 900);

  let response = mcp_tool(
    &server,
    "stove_failure_detail",
    json!({ "run_id": "run-1", "test_id": "test-1" }),
  )
  .await;
  let content = &response["result"]["structuredContent"];

  assert_eq!(content["app_name"], "checkout-api");
  assert_eq!(content["timeline_summary"]["failed_entries"], 1);
  assert_eq!(content["trace_summary"]["trace_status"], "correlated");
  assert_eq!(content["trace_summary"]["exception_spans"], 1);
  assert_eq!(content["snapshot_summaries"][0]["system"], "Kafka");
  assert_eq!(
    content["failed_entries"][0]["input"]["authorization"],
    "[REDACTED]"
  );
}

#[tokio::test]
async fn mock_interactions_and_warnings_reach_agents() {
  let server = TestServer::start().await;
  server.seed_run("run-1", "checkout-api");
  server.seed_test("run-1", "test-1", "charges the card", "CheckoutSpec");
  server.seed_entry("run-1", "test-1", "Kafka", "waitUntilConsumed", "FAILED");
  // The test's own unmatched exchange, carrying the near-miss diagnosis.
  server.seed_mock_interaction(
    "run-1",
    Some("test-1"),
    "/payments/charge",
    false,
    "404",
    "PROVEN_HEADER",
    &["closest stub differed in request body $.amount (expected 100, got 99)"],
  );
  // Unattributed evidence: run-level lane, never guessed into a test.
  server.seed_mock_interaction(
    "run-1",
    None,
    "/ambient/probe",
    false,
    "404",
    "UNATTRIBUTED",
    &[],
  );
  server.seed_mock_warning(
    "run-1",
    Some("test-1"),
    "UNUSED_STUB",
    "Stub GET /never was registered by this test but never matched.",
  );
  server.end_test_failed("run-1", "test-1", 800, "timeout");
  server.end_run("run-1", 0, 1, 900);

  // failure_detail carries the near-miss diagnosis in the compact packet.
  let detail = mcp_tool(
    &server,
    "stove_failure_detail",
    json!({ "run_id": "run-1", "test_id": "test-1" }),
  )
  .await;
  let detail_content = &detail["result"]["structuredContent"];
  assert_eq!(
    detail_content["unmatched_interactions"][0]["target"],
    "/payments/charge"
  );
  assert!(
    detail_content["unmatched_interactions"][0]["near_misses"][0]
      .as_str()
      .unwrap()
      .contains("$.amount")
  );
  assert_eq!(detail_content["mock_warnings"][0]["kind"], "UNUSED_STUB");
  assert_eq!(
    detail_content["interactions_tool_call"]["tool"],
    "stove_interactions"
  );
  // Bodies go through the same redaction as every other evidence surface.
  assert_eq!(
    detail_content["unmatched_interactions"][0]["request_body"]["authorization"],
    "[REDACTED]"
  );

  // Test scope sees only the test's own exchange; run scope adds the ambient lane.
  let test_scope = mcp_tool(
    &server,
    "stove_interactions",
    json!({ "run_id": "run-1", "test_id": "test-1" }),
  )
  .await;
  let test_content = &test_scope["result"]["structuredContent"];
  assert_eq!(test_content["scope"], "test");
  assert_eq!(test_content["total_interactions"], 1);
  assert_eq!(test_content["warnings"][0]["kind"], "UNUSED_STUB");

  let run_scope = mcp_tool(&server, "stove_interactions", json!({ "run_id": "run-1" })).await;
  let run_content = &run_scope["result"]["structuredContent"];
  assert_eq!(run_content["scope"], "run");
  assert_eq!(run_content["total_interactions"], 2);
  assert_eq!(run_content["unattributed_interactions"], 1);

  // The timeline interleaves the exchange with report entries, tagged by type.
  let timeline = mcp_tool(
    &server,
    "stove_timeline",
    json!({ "run_id": "run-1", "test_id": "test-1", "focus": "all" }),
  )
  .await;
  let events = timeline["result"]["structuredContent"]["events"]
    .as_array()
    .unwrap();
  assert!(
    events
      .iter()
      .any(|event| event["type"] == "mock_interaction" && event["target"] == "/payments/charge")
  );

  // Raw drill-down by id works through the interaction's own raw_tool_call.
  let interaction_id = test_content["interactions"][0]["id"].clone();
  let raw = mcp_tool(
    &server,
    "stove_raw_evidence",
    json!({ "kind": "interaction", "id": interaction_id, "run_id": "run-1" }),
  )
  .await;
  assert_eq!(
    raw["result"]["structuredContent"]["raw_evidence"]["evidence"]["target"],
    "/payments/charge"
  );
}

#[tokio::test]
async fn mcp_handles_no_failures_and_caps_oversized_detail() {
  let server = TestServer::start().await;

  server.seed_run("run-pass", "checkout-api");
  server.seed_test("run-pass", "test-pass", "happy path", "CheckoutSpec");
  server.end_test("run-pass", "test-pass", 100);
  server.end_run("run-pass", 1, 0, 100);

  let no_failures = mcp_tool(&server, "stove_failures", json!({ "run_id": "run-pass" })).await;
  let no_failure_content = &no_failures["result"]["structuredContent"];
  assert_eq!(no_failure_content["failure_count"], 0);
  assert_eq!(no_failure_content["groups"].as_array().unwrap().len(), 0);

  server.seed_run("run-big", "checkout-api");
  server.seed_test("run-big", "test-big", "large payload", "CheckoutSpec");
  let oversized = format!(r#"{{"payload":"{}"}}"#, "x".repeat(400));
  server.seed_entry_full(
    "run-big",
    "test-big",
    "HTTP",
    "POST /checkout",
    "FAILED",
    &oversized,
    "",
    "",
    "",
    "large failure",
    "",
  );
  for index in 0..5 {
    server.seed_snapshot(
      "run-big",
      "test-big",
      "Kafka",
      r#"{"published":[]}"#,
      &format!("snapshot {index}"),
    );
  }
  server.end_test_failed("run-big", "test-big", 200, "large failure");
  server.end_run("run-big", 0, 1, 200);

  let detail = mcp_tool(
    &server,
    "stove_failure_detail",
    json!({ "run_id": "run-big", "test_id": "test-big", "budget": "tiny", "max_chars": 120 }),
  )
  .await;
  let detail_content = &detail["result"]["structuredContent"];

  assert!(
    detail_content["failed_entries"][0]["input"]["payload"]
      .as_str()
      .unwrap()
      .contains("<truncated")
  );
  assert_eq!(
    detail_content["snapshot_summaries"]
      .as_array()
      .unwrap()
      .len(),
    3
  );
  assert_eq!(detail_content["omitted"]["snapshots"], 2);
}

#[tokio::test]
async fn mcp_flushes_pending_ingest_before_reads() {
  let (server, ingestor) = TestServer::start_with_ingestor().await;
  let service =
    DashboardEventServiceImpl::new_with_ingestor(server.repo.clone(), server.sse.clone(), ingestor);

  send_event(&service, run_started("run-pending", "checkout-api")).await;
  send_event(&service, test_started("run-pending", "test-pending")).await;
  send_event(&service, test_ended_failed("run-pending", "test-pending")).await;

  let response = mcp_tool(
    &server,
    "stove_failures",
    json!({ "run_id": "run-pending" }),
  )
  .await;
  let groups = response["result"]["structuredContent"]["groups"]
    .as_array()
    .unwrap();

  assert_eq!(groups.len(), 1);
  assert_eq!(groups[0]["failures"][0]["test_id"], "test-pending");
  assert_eq!(groups[0]["run_status"], "RUNNING");
}

#[tokio::test]
async fn mcp_rejects_non_local_host_headers() {
  let server = TestServer::start().await;

  let response = server
    .client
    .post(server.mcp_url())
    .header(reqwest::header::HOST, "example.com")
    .json(&json!({
      "jsonrpc": "2.0",
      "id": 1,
      "method": "tools/list"
    }))
    .send()
    .await
    .expect("request should complete");

  assert_eq!(response.status(), StatusCode::FORBIDDEN);
}

fn seed_multi_app_failures(server: &TestServer) {
  server.seed_run_at(
    "run-checkout-1",
    "checkout-api",
    "2024-06-01T10:00:00Z",
    &["HTTP"],
  );
  server.seed_test(
    "run-checkout-1",
    "duplicate-name",
    "same display name",
    "CheckoutSpec",
  );
  server.end_test_failed(
    "run-checkout-1",
    "duplicate-name",
    100,
    "first checkout failure",
  );
  server.end_run("run-checkout-1", 0, 1, 100);

  server.seed_run_at(
    "run-catalog-1",
    "catalog-api",
    "2024-06-01T10:01:00Z",
    &["HTTP"],
  );
  server.seed_test(
    "run-catalog-1",
    "duplicate-name",
    "same display name",
    "CatalogSpec",
  );
  server.end_test_failed("run-catalog-1", "duplicate-name", 100, "catalog failure");
  server.end_run("run-catalog-1", 0, 1, 100);

  server.seed_run_at(
    "run-checkout-2",
    "checkout-api",
    "2024-06-01T10:02:00Z",
    &["Kafka"],
  );
  server.seed_test(
    "run-checkout-2",
    "duplicate-name",
    "same display name",
    "CheckoutSpec",
  );
  server.end_test_failed(
    "run-checkout-2",
    "duplicate-name",
    100,
    "latest checkout failure",
  );
  server.end_run("run-checkout-2", 0, 1, 100);
}

async fn send_event(service: &DashboardEventServiceImpl, event: proto::DashboardEvent) {
  DashboardEventService::send_event(service, Request::new(event))
    .await
    .expect("event should be accepted");
}

fn timestamp() -> Option<prost_types::Timestamp> {
  Some(prost_types::Timestamp {
    seconds: 1_704_067_200,
    nanos: 0,
  })
}

fn run_started(run_id: &str, app_name: &str) -> proto::DashboardEvent {
  proto::DashboardEvent {
    run_id: run_id.to_string(),
    event: Some(proto::dashboard_event::Event::RunStarted(
      proto::RunStartedEvent {
        timestamp: timestamp(),
        app_name: app_name.to_string(),
        systems: vec!["HTTP".to_string()],
        stove_version: "0.23.2".to_string(),
      },
    )),
  }
}

fn test_started(run_id: &str, test_id: &str) -> proto::DashboardEvent {
  proto::DashboardEvent {
    run_id: run_id.to_string(),
    event: Some(proto::dashboard_event::Event::TestStarted(
      proto::TestStartedEvent {
        test_id: test_id.to_string(),
        test_name: "pending failure".to_string(),
        spec_name: "PendingSpec".to_string(),
        timestamp: timestamp(),
        test_path: vec!["pending".to_string()],
      },
    )),
  }
}

fn test_ended_failed(run_id: &str, test_id: &str) -> proto::DashboardEvent {
  proto::DashboardEvent {
    run_id: run_id.to_string(),
    event: Some(proto::dashboard_event::Event::TestEnded(
      proto::TestEndedEvent {
        test_id: test_id.to_string(),
        status: "FAILED".to_string(),
        duration_ms: 42,
        error: "pending failure".to_string(),
        timestamp: timestamp(),
      },
    )),
  }
}
