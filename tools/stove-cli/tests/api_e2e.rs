//! End-to-end tests for the Stove CLI REST API.
//!
//! Each test spins up a real axum server on an OS-assigned port backed by an
//! in-memory SQLite database, then exercises the HTTP endpoints with `reqwest`.
//! This gives us true black-box regression coverage of the full request path:
//! routing -> handler -> repository -> SQLite -> JSON serialization.

mod common;

use common::TestServer;
use reqwest::StatusCode;
use serde_json::Value;
use std::collections::HashMap;
use std::sync::Arc;
use std::time::Duration;
use stove::grpc::service::PortalEventServiceImpl;
use stove::proto;
use stove::proto::portal_event_service_server::PortalEventService;
use tonic::Request;

fn ts(seconds: i64, nanos: i32) -> Option<prost_types::Timestamp> {
  Some(prost_types::Timestamp { seconds, nanos })
}

fn run_started_event(run_id: &str, app_name: &str, seconds: i64, nanos: i32) -> proto::PortalEvent {
  proto::PortalEvent {
    run_id: run_id.to_string(),
    event: Some(proto::portal_event::Event::RunStarted(
      proto::RunStartedEvent {
        timestamp: ts(seconds, nanos),
        app_name: app_name.to_string(),
        systems: vec!["HTTP".to_string(), "Kafka".to_string()],
      },
    )),
  }
}

fn run_ended_event(
  run_id: &str,
  total_tests: i32,
  passed: i32,
  failed: i32,
  duration_ms: i64,
  seconds: i64,
  nanos: i32,
) -> proto::PortalEvent {
  proto::PortalEvent {
    run_id: run_id.to_string(),
    event: Some(proto::portal_event::Event::RunEnded(proto::RunEndedEvent {
      timestamp: ts(seconds, nanos),
      total_tests,
      passed,
      failed,
      duration_ms,
    })),
  }
}

fn test_started_event(
  run_id: &str,
  test_id: &str,
  test_name: &str,
  spec_name: &str,
  seconds: i64,
  nanos: i32,
) -> proto::PortalEvent {
  proto::PortalEvent {
    run_id: run_id.to_string(),
    event: Some(proto::portal_event::Event::TestStarted(
      proto::TestStartedEvent {
        test_id: test_id.to_string(),
        test_name: test_name.to_string(),
        spec_name: spec_name.to_string(),
        timestamp: ts(seconds, nanos),
      },
    )),
  }
}

fn test_ended_event(
  run_id: &str,
  test_id: &str,
  status: &str,
  duration_ms: i64,
  error: &str,
  seconds: i64,
  nanos: i32,
) -> proto::PortalEvent {
  proto::PortalEvent {
    run_id: run_id.to_string(),
    event: Some(proto::portal_event::Event::TestEnded(
      proto::TestEndedEvent {
        test_id: test_id.to_string(),
        status: status.to_string(),
        duration_ms,
        error: error.to_string(),
        timestamp: ts(seconds, nanos),
      },
    )),
  }
}

fn entry_recorded_event(
  run_id: &str,
  test_id: &str,
  action: &str,
  result: &str,
  trace_id: &str,
  seconds: i64,
  nanos: i32,
) -> proto::PortalEvent {
  proto::PortalEvent {
    run_id: run_id.to_string(),
    event: Some(proto::portal_event::Event::EntryRecorded(
      proto::EntryRecordedEvent {
        test_id: test_id.to_string(),
        timestamp: ts(seconds, nanos),
        system: "HTTP".to_string(),
        action: action.to_string(),
        result: result.to_string(),
        input: String::new(),
        output: String::new(),
        metadata: HashMap::default(),
        expected: String::new(),
        actual: String::new(),
        error: String::new(),
        trace_id: trace_id.to_string(),
      },
    )),
  }
}

fn span_recorded_event(
  run_id: &str,
  trace_id: &str,
  span_id: &str,
  parent_span_id: &str,
  operation_name: &str,
  service_name: &str,
  start_time_nanos: i64,
  end_time_nanos: i64,
) -> proto::PortalEvent {
  proto::PortalEvent {
    run_id: run_id.to_string(),
    event: Some(proto::portal_event::Event::SpanRecorded(
      proto::SpanRecordedEvent {
        trace_id: trace_id.to_string(),
        span_id: span_id.to_string(),
        parent_span_id: parent_span_id.to_string(),
        operation_name: operation_name.to_string(),
        service_name: service_name.to_string(),
        start_time_nanos,
        end_time_nanos,
        status: "OK".to_string(),
        attributes: HashMap::default(),
        exception: None,
      },
    )),
  }
}

fn snapshot_event(
  run_id: &str,
  test_id: &str,
  system: &str,
  state_json: &str,
  summary: &str,
) -> proto::PortalEvent {
  proto::PortalEvent {
    run_id: run_id.to_string(),
    event: Some(proto::portal_event::Event::Snapshot(proto::SnapshotEvent {
      test_id: test_id.to_string(),
      system: system.to_string(),
      state_json: state_json.to_string(),
      summary: summary.to_string(),
    })),
  }
}

async fn send_event(
  service: &PortalEventServiceImpl,
  event: proto::PortalEvent,
) -> Result<(), tonic::Status> {
  PortalEventService::send_event(service, Request::new(event))
    .await
    .map(|_| ())
}

async fn flush_events(service: &PortalEventServiceImpl) {
  service
    .flush_pending()
    .await
    .expect("queued portal events should flush");
}

fn extract_sse_data_frame(frame: &str) -> Option<String> {
  let data_lines: Vec<&str> = frame
    .lines()
    .filter_map(|line| line.strip_prefix("data:").map(str::trim_start))
    .collect();

  if data_lines.is_empty() {
    None
  } else {
    Some(data_lines.join("\n"))
  }
}

async fn next_sse_data(
  resp: &mut reqwest::Response,
  buffer: &mut String,
) -> Result<String, Box<dyn std::error::Error>> {
  loop {
    if let Some(frame_end) = buffer.find("\n\n") {
      let frame = buffer[..frame_end].to_string();
      buffer.drain(..frame_end + 2);
      if let Some(data) = extract_sse_data_frame(&frame) {
        return Ok(data);
      }
    }

    let chunk = tokio::time::timeout(std::time::Duration::from_secs(5), resp.chunk()).await??;
    let chunk = chunk.ok_or("SSE stream ended before the next event")?;
    buffer.push_str(std::str::from_utf8(&chunk)?);
  }
}

// ---------------------------------------------------------------------------
// GET /api/v1/apps
// ---------------------------------------------------------------------------

#[tokio::test]
async fn apps_returns_empty_when_no_data() {
  let server = TestServer::start().await;

  let body = server.get_json("/apps").await;
  assert_eq!(body, Value::Array(vec![]));
}

#[tokio::test]
async fn apps_returns_app_summaries() {
  let server = TestServer::start().await;
  server.seed_full_run();

  let body = server.get_json("/apps").await;
  let apps = body.as_array().expect("should be array");
  assert_eq!(apps.len(), 1);
  assert_eq!(apps[0]["app_name"], "product-api");
  assert_eq!(apps[0]["latest_run_id"], "run-1");
  assert_eq!(apps[0]["latest_status"], "FAILED");
  assert_eq!(apps[0]["total_runs"], 1);
}

#[tokio::test]
async fn apps_returns_multiple_apps() {
  let server = TestServer::start().await;
  server.seed_run("run-a", "alpha-api");
  server.seed_run("run-b", "beta-api");

  let body = server.get_json("/apps").await;
  let apps = body.as_array().unwrap();
  assert_eq!(apps.len(), 2);

  let names: Vec<&str> = apps
    .iter()
    .map(|a| a["app_name"].as_str().unwrap())
    .collect();
  assert!(names.contains(&"alpha-api"));
  assert!(names.contains(&"beta-api"));
}

// ---------------------------------------------------------------------------
// GET /api/v1/runs
// ---------------------------------------------------------------------------

#[tokio::test]
async fn runs_returns_all_runs() {
  let server = TestServer::start().await;
  server.seed_full_run();

  let body = server.get_json("/runs").await;
  let runs = body.as_array().unwrap();
  assert_eq!(runs.len(), 1);
  assert_eq!(runs[0]["id"], "run-1");
  assert_eq!(runs[0]["app_name"], "product-api");
  assert_eq!(runs[0]["status"], "FAILED");
  assert_eq!(runs[0]["total_tests"], 2);
  assert_eq!(runs[0]["passed"], 1);
  assert_eq!(runs[0]["failed"], 1);
  assert_eq!(runs[0]["duration_ms"], 10000);

  let systems = runs[0]["systems"].as_array().unwrap();
  assert_eq!(systems.len(), 3);
  assert!(systems.contains(&Value::String("HTTP".into())));
  assert!(systems.contains(&Value::String("Kafka".into())));
}

#[tokio::test]
async fn runs_filters_by_app_name() {
  let server = TestServer::start().await;
  server.seed_run("run-a", "alpha-api");
  server.seed_run("run-b", "beta-api");

  let body = server.get_json("/runs?app=alpha-api").await;
  let runs = body.as_array().unwrap();
  assert_eq!(runs.len(), 1);
  assert_eq!(runs[0]["app_name"], "alpha-api");
}

#[tokio::test]
async fn runs_returns_empty_for_unknown_app() {
  let server = TestServer::start().await;
  server.seed_full_run();

  let body = server.get_json("/runs?app=nonexistent").await;
  assert_eq!(body, Value::Array(vec![]));
}

// ---------------------------------------------------------------------------
// GET /api/v1/runs/:run_id
// ---------------------------------------------------------------------------

#[tokio::test]
async fn get_run_returns_single_run() {
  let server = TestServer::start().await;
  server.seed_full_run();

  let body = server.get_json("/runs/run-1").await;
  assert_eq!(body["id"], "run-1");
  assert_eq!(body["app_name"], "product-api");
  assert_eq!(body["started_at"], "2024-06-01T10:00:00Z");
  assert_eq!(body["ended_at"], "2024-06-01T10:00:10Z");
}

#[tokio::test]
async fn get_run_returns_null_for_unknown_id() {
  let server = TestServer::start().await;

  let body = server.get_json("/runs/nonexistent").await;
  assert_eq!(body, Value::Null);
}

// ---------------------------------------------------------------------------
// GET /api/v1/runs/:run_id/tests
// ---------------------------------------------------------------------------

#[tokio::test]
async fn get_tests_returns_tests_for_run() {
  let server = TestServer::start().await;
  server.seed_full_run();

  let body = server.get_json("/runs/run-1/tests").await;
  let tests = body.as_array().unwrap();
  assert_eq!(tests.len(), 2);

  assert_eq!(tests[0]["test_name"], "should create product");
  assert_eq!(tests[0]["spec_name"], "ProductSpec");
  assert_eq!(tests[0]["status"], "PASSED");
  assert_eq!(tests[0]["duration_ms"], 1500);
  assert!(tests[0]["error"].is_null());

  assert_eq!(tests[1]["test_name"], "should reject duplicate");
  assert_eq!(tests[1]["status"], "FAILED");
  assert_eq!(tests[1]["error"], "Expected conflict but got success");
}

#[tokio::test]
async fn get_tests_returns_empty_for_unknown_run() {
  let server = TestServer::start().await;

  let body = server.get_json("/runs/nonexistent/tests").await;
  assert_eq!(body, Value::Array(vec![]));
}

#[tokio::test]
async fn concurrent_running_tests_are_visible_via_api_while_run_is_in_progress() {
  let server = TestServer::start().await;
  let service = Arc::new(PortalEventServiceImpl::new(
    server.repo.clone(),
    server.sse.clone(),
  ));

  send_event(
    service.as_ref(),
    run_started_event("run-concurrent", "concurrent-app", 1_704_067_200, 0),
  )
  .await
  .unwrap();

  let service_a = service.clone();
  let service_b = service.clone();
  tokio::try_join!(
    async move {
      send_event(
        service_a.as_ref(),
        test_started_event(
          "run-concurrent",
          "test-a",
          "handles checkout",
          "ConcurrentSpec",
          1_704_067_201,
          0,
        ),
      )
      .await
    },
    async move {
      send_event(
        service_b.as_ref(),
        test_started_event(
          "run-concurrent",
          "test-b",
          "handles payment",
          "ConcurrentSpec",
          1_704_067_201,
          0,
        ),
      )
      .await
    },
  )
  .unwrap();

  let service_a = service.clone();
  let service_b = service.clone();
  tokio::try_join!(
    async move {
      send_event(
        service_a.as_ref(),
        entry_recorded_event(
          "run-concurrent",
          "test-a",
          "GET /checkout",
          "PASSED",
          "trace-a",
          1_704_067_202,
          0,
        ),
      )
      .await
    },
    async move {
      send_event(
        service_b.as_ref(),
        entry_recorded_event(
          "run-concurrent",
          "test-b",
          "POST /payment",
          "PASSED",
          "trace-b",
          1_704_067_202,
          0,
        ),
      )
      .await
    },
  )
  .unwrap();

  flush_events(service.as_ref()).await;

  let run = server.get_json("/runs/run-concurrent").await;
  assert_eq!(run["status"], "RUNNING");

  let tests = server.get_json("/runs/run-concurrent/tests").await;
  let tests = tests.as_array().unwrap();
  assert_eq!(tests.len(), 2);

  let test_a = tests.iter().find(|test| test["id"] == "test-a").unwrap();
  assert_eq!(test_a["status"], "RUNNING");
  assert!(test_a["ended_at"].is_null());

  let test_b = tests.iter().find(|test| test["id"] == "test-b").unwrap();
  assert_eq!(test_b["status"], "RUNNING");
  assert!(test_b["ended_at"].is_null());

  let entries_a = server
    .get_json("/runs/run-concurrent/tests/test-a/entries")
    .await;
  let entries_a = entries_a.as_array().unwrap();
  assert_eq!(entries_a.len(), 1);
  assert_eq!(entries_a[0]["action"], "GET /checkout");

  let entries_b = server
    .get_json("/runs/run-concurrent/tests/test-b/entries")
    .await;
  let entries_b = entries_b.as_array().unwrap();
  assert_eq!(entries_b.len(), 1);
  assert_eq!(entries_b[0]["action"], "POST /payment");
}

#[tokio::test]
async fn concurrent_interleaved_test_lifecycle_remains_isolated_across_api_views() {
  let server = TestServer::start().await;
  let service = Arc::new(PortalEventServiceImpl::new(
    server.repo.clone(),
    server.sse.clone(),
  ));

  send_event(
    service.as_ref(),
    run_started_event("run-interleaved", "concurrent-app", 1_704_067_300, 0),
  )
  .await
  .unwrap();

  let service_a = service.clone();
  let service_b = service.clone();
  tokio::try_join!(
    async move {
      send_event(
        service_a.as_ref(),
        test_started_event(
          "run-interleaved",
          "test-a",
          "handles checkout",
          "ConcurrentSpec",
          1_704_067_301,
          0,
        ),
      )
      .await
    },
    async move {
      send_event(
        service_b.as_ref(),
        test_started_event(
          "run-interleaved",
          "test-b",
          "handles payment",
          "ConcurrentSpec",
          1_704_067_301,
          0,
        ),
      )
      .await
    },
  )
  .unwrap();

  let service_a = service.clone();
  let service_b = service.clone();
  tokio::try_join!(
    async move {
      send_event(
        service_a.as_ref(),
        entry_recorded_event(
          "run-interleaved",
          "test-a",
          "GET /checkout",
          "PASSED",
          "trace-a",
          1_704_067_302,
          0,
        ),
      )
      .await
    },
    async move {
      send_event(
        service_b.as_ref(),
        entry_recorded_event(
          "run-interleaved",
          "test-b",
          "POST /payment",
          "FAILED",
          "trace-b",
          1_704_067_302,
          0,
        ),
      )
      .await
    },
  )
  .unwrap();

  let service_a = service.clone();
  let service_b = service.clone();
  tokio::try_join!(
    async move {
      send_event(
        service_a.as_ref(),
        span_recorded_event(
          "run-interleaved",
          "trace-a",
          "span-a",
          "",
          "GET /checkout",
          "checkout-api",
          1_000_000_000,
          1_100_000_000,
        ),
      )
      .await
    },
    async move {
      send_event(
        service_b.as_ref(),
        span_recorded_event(
          "run-interleaved",
          "trace-b",
          "span-b",
          "",
          "POST /payment",
          "payment-api",
          1_200_000_000,
          1_350_000_000,
        ),
      )
      .await
    },
  )
  .unwrap();

  let service_a = service.clone();
  let service_b = service.clone();
  tokio::try_join!(
    async move {
      send_event(
        service_a.as_ref(),
        snapshot_event(
          "run-interleaved",
          "test-a",
          "Kafka",
          r#"{"published":1}"#,
          "1 published",
        ),
      )
      .await
    },
    async move {
      send_event(
        service_b.as_ref(),
        snapshot_event(
          "run-interleaved",
          "test-b",
          "Redis",
          r#"{"keys":2}"#,
          "2 keys",
        ),
      )
      .await
    },
  )
  .unwrap();

  let service_a = service.clone();
  let service_b = service.clone();
  tokio::try_join!(
    async move {
      send_event(
        service_a.as_ref(),
        test_ended_event(
          "run-interleaved",
          "test-a",
          "PASSED",
          1_200,
          "",
          1_704_067_303,
          0,
        ),
      )
      .await
    },
    async move {
      send_event(
        service_b.as_ref(),
        test_ended_event(
          "run-interleaved",
          "test-b",
          "FAILED",
          1_500,
          "payment timeout",
          1_704_067_303,
          0,
        ),
      )
      .await
    },
  )
  .unwrap();

  send_event(
    service.as_ref(),
    run_ended_event("run-interleaved", 2, 1, 1, 3_000, 1_704_067_304, 0),
  )
  .await
  .unwrap();

  flush_events(service.as_ref()).await;

  let run = server.get_json("/runs/run-interleaved").await;
  assert_eq!(run["status"], "FAILED");
  assert_eq!(run["total_tests"], 2);
  assert_eq!(run["passed"], 1);
  assert_eq!(run["failed"], 1);

  let tests = server.get_json("/runs/run-interleaved/tests").await;
  let tests = tests.as_array().unwrap();
  assert_eq!(tests.len(), 2);

  let test_a = tests.iter().find(|test| test["id"] == "test-a").unwrap();
  assert_eq!(test_a["status"], "PASSED");
  assert!(test_a["error"].is_null());

  let test_b = tests.iter().find(|test| test["id"] == "test-b").unwrap();
  assert_eq!(test_b["status"], "FAILED");
  assert_eq!(test_b["error"], "payment timeout");

  let spans_a = server
    .get_json("/runs/run-interleaved/tests/test-a/spans")
    .await;
  let spans_a = spans_a.as_array().unwrap();
  assert_eq!(spans_a.len(), 1);
  assert_eq!(spans_a[0]["span_id"], "span-a");

  let spans_b = server
    .get_json("/runs/run-interleaved/tests/test-b/spans")
    .await;
  let spans_b = spans_b.as_array().unwrap();
  assert_eq!(spans_b.len(), 1);
  assert_eq!(spans_b[0]["span_id"], "span-b");

  let snapshots_a = server
    .get_json("/runs/run-interleaved/tests/test-a/snapshots")
    .await;
  let snapshots_a = snapshots_a.as_array().unwrap();
  assert_eq!(snapshots_a.len(), 1);
  assert_eq!(snapshots_a[0]["system"], "Kafka");

  let snapshots_b = server
    .get_json("/runs/run-interleaved/tests/test-b/snapshots")
    .await;
  let snapshots_b = snapshots_b.as_array().unwrap();
  assert_eq!(snapshots_b.len(), 1);
  assert_eq!(snapshots_b[0]["system"], "Redis");
}

// ---------------------------------------------------------------------------
// GET /api/v1/runs/:run_id/tests/:test_id/entries
// ---------------------------------------------------------------------------

#[tokio::test]
async fn get_entries_returns_entries_for_test() {
  let server = TestServer::start().await;
  server.seed_full_run();

  let body = server.get_json("/runs/run-1/tests/test-1/entries").await;
  let entries = body.as_array().unwrap();
  assert_eq!(entries.len(), 1);
  assert_eq!(entries[0]["system"], "HTTP");
  assert_eq!(entries[0]["action"], "POST /products");
  assert_eq!(entries[0]["result"], "PASSED");
  assert_eq!(entries[0]["input"], r#"{"name":"widget"}"#);
  assert_eq!(entries[0]["output"], r#"{"id":42}"#);
  assert_eq!(entries[0]["trace_id"], "trace-abc");
}

#[tokio::test]
async fn get_entries_isolates_by_test_id() {
  let server = TestServer::start().await;
  server.seed_full_run();

  let body = server.get_json("/runs/run-1/tests/test-2/entries").await;
  let entries = body.as_array().unwrap();
  assert_eq!(entries.len(), 1);
  assert_eq!(entries[0]["result"], "FAILED");
  assert_eq!(entries[0]["expected"], "409 Conflict");
  assert_eq!(entries[0]["actual"], "201 Created");
}

#[tokio::test]
async fn get_entries_returns_empty_for_unknown_test() {
  let server = TestServer::start().await;
  server.seed_full_run();

  let body = server
    .get_json("/runs/run-1/tests/nonexistent/entries")
    .await;
  assert_eq!(body, Value::Array(vec![]));
}

// ---------------------------------------------------------------------------
// GET /api/v1/runs/:run_id/tests/:test_id/spans
// ---------------------------------------------------------------------------

#[tokio::test]
async fn get_test_spans_returns_spans_linked_via_trace_id() {
  let server = TestServer::start().await;
  server.seed_full_run();

  let body = server.get_json("/runs/run-1/tests/test-1/spans").await;
  let spans = body.as_array().unwrap();
  assert_eq!(spans.len(), 2);
  assert_eq!(spans[0]["operation_name"], "POST /products");
  assert_eq!(spans[0]["status"], "OK");
  assert_eq!(spans[1]["operation_name"], "INSERT INTO products");
  assert_eq!(spans[1]["parent_span_id"], "span-1");
}

#[tokio::test]
async fn get_test_spans_returns_empty_when_no_trace_linked() {
  let server = TestServer::start().await;
  server.seed_full_run();

  let body = server.get_json("/runs/run-1/tests/test-2/spans").await;
  let spans = body.as_array().unwrap();
  assert_eq!(spans.len(), 0);
}

#[tokio::test]
async fn get_test_spans_returns_spans_linked_via_attribute() {
  let server = TestServer::start().await;
  server.seed_run("run-1", "my-app");
  server.seed_test("run-1", "test-attr", "attribute test", "TracingSpec");
  // Entry without trace_id — spans linked only via attributes
  server.seed_entry("run-1", "test-attr", "HTTP", "GET /items", "PASSED");
  // Root span with x-stove-test-id attribute
  server.seed_span_timed(
    "run-1",
    "trace-xyz",
    "s1",
    "",
    "GET /items",
    "my-app",
    1_000_000_000,
    1_100_000_000,
    r#"{"x-stove-test-id":"test-attr","http.method":"GET"}"#,
  );
  // Child span in the same trace without the attribute
  server.seed_span_timed(
    "run-1",
    "trace-xyz",
    "s2",
    "s1",
    "SELECT items",
    "my-db",
    1_020_000_000,
    1_080_000_000,
    r#"{"db.system":"postgresql"}"#,
  );
  server.end_test("run-1", "test-attr", 500);

  let body = server.get_json("/runs/run-1/tests/test-attr/spans").await;
  let spans = body.as_array().unwrap();
  assert_eq!(spans.len(), 2, "both spans in the trace should appear");
  assert_eq!(spans[0]["span_id"], "s1");
  assert_eq!(spans[1]["span_id"], "s2");
}

#[tokio::test]
async fn get_test_spans_does_not_cross_match_similar_test_ids_in_attributes() {
  let server = TestServer::start().await;
  server.seed_run("run-1", "my-app");
  server.seed_test("run-1", "test-1", "first test", "TracingSpec");
  server.seed_test("run-1", "test-10", "tenth test", "TracingSpec");
  server.seed_span_timed(
    "run-1",
    "trace-10",
    "span-10",
    "",
    "GET /ten",
    "my-app",
    1_000_000_000,
    1_100_000_000,
    r#"{"x-stove-test-id":"test-10","http.method":"GET"}"#,
  );

  let body = server.get_json("/runs/run-1/tests/test-1/spans").await;
  let spans = body.as_array().unwrap();

  assert_eq!(
    spans.len(),
    0,
    "test-1 should not receive spans from test-10"
  );
}

#[tokio::test]
async fn get_test_spans_combines_entry_and_attribute_linked_traces() {
  let server = TestServer::start().await;
  server.seed_run("run-1", "my-app");
  server.seed_test("run-1", "test-combo", "combo test", "TracingSpec");
  // Entry links to trace-a
  server.seed_entry_full(
    "run-1",
    "test-combo",
    "HTTP",
    "POST /orders",
    "PASSED",
    "",
    "",
    "",
    "",
    "",
    "trace-a",
  );
  // Span in trace-a (linked via entry)
  server.seed_span_timed(
    "run-1",
    "trace-a",
    "sa1",
    "",
    "POST /orders",
    "order-svc",
    1_000_000_000,
    1_100_000_000,
    "{}",
  );
  // Span in trace-b (linked via attribute only)
  server.seed_span_timed(
    "run-1",
    "trace-b",
    "sb1",
    "",
    "process-event",
    "worker",
    2_000_000_000,
    2_200_000_000,
    r#"{"x-stove-test-id":"test-combo"}"#,
  );
  server.end_test("run-1", "test-combo", 1000);

  let body = server.get_json("/runs/run-1/tests/test-combo/spans").await;
  let spans = body.as_array().unwrap();
  assert_eq!(spans.len(), 2);
  let span_ids: Vec<&str> = spans
    .iter()
    .map(|s| s["span_id"].as_str().unwrap())
    .collect();
  assert!(span_ids.contains(&"sa1"), "entry-linked span");
  assert!(span_ids.contains(&"sb1"), "attribute-linked span");
}

#[tokio::test]
async fn get_test_spans_returns_full_trace_when_one_span_has_attribute() {
  let server = TestServer::start().await;
  server.seed_run("run-1", "my-app");
  server.seed_test("run-1", "test-full", "full trace test", "TracingSpec");
  server.seed_entry("run-1", "test-full", "HTTP", "GET /", "PASSED");
  // Only root span has the test-id attribute
  server.seed_span_timed(
    "run-1",
    "trace-full",
    "root",
    "",
    "GET /",
    "gateway",
    1_000_000_000,
    1_500_000_000,
    r#"{"x-stove-test-id":"test-full"}"#,
  );
  // Children don't have the attribute
  server.seed_span_timed(
    "run-1",
    "trace-full",
    "child-1",
    "root",
    "auth-check",
    "auth-svc",
    1_050_000_000,
    1_150_000_000,
    r#"{"auth.type":"jwt"}"#,
  );
  server.seed_span_timed(
    "run-1",
    "trace-full",
    "child-2",
    "root",
    "db-query",
    "db-svc",
    1_200_000_000,
    1_400_000_000,
    r#"{"db.system":"mysql"}"#,
  );
  server.end_test("run-1", "test-full", 2000);

  let body = server.get_json("/runs/run-1/tests/test-full/spans").await;
  let spans = body.as_array().unwrap();
  assert_eq!(spans.len(), 3, "all spans in the trace should be returned");
  assert_eq!(spans[0]["span_id"], "root");
  assert_eq!(spans[1]["span_id"], "child-1");
  assert_eq!(spans[2]["span_id"], "child-2");
}

#[tokio::test]
async fn get_test_spans_ordered_by_start_time() {
  let server = TestServer::start().await;
  server.seed_run("run-1", "my-app");
  server.seed_test("run-1", "test-order", "ordering test", "TracingSpec");
  server.seed_entry_full(
    "run-1",
    "test-order",
    "HTTP",
    "GET /",
    "PASSED",
    "",
    "",
    "",
    "",
    "",
    "trace-ord",
  );
  // Insert spans out of chronological order
  server.seed_span_timed(
    "run-1",
    "trace-ord",
    "late",
    "",
    "late-op",
    "svc",
    3_000_000_000,
    3_500_000_000,
    "{}",
  );
  server.seed_span_timed(
    "run-1",
    "trace-ord",
    "early",
    "",
    "early-op",
    "svc",
    1_000_000_000,
    1_500_000_000,
    "{}",
  );
  server.seed_span_timed(
    "run-1",
    "trace-ord",
    "mid",
    "",
    "mid-op",
    "svc",
    2_000_000_000,
    2_500_000_000,
    "{}",
  );
  server.end_test("run-1", "test-order", 1000);

  let body = server.get_json("/runs/run-1/tests/test-order/spans").await;
  let spans = body.as_array().unwrap();
  assert_eq!(spans.len(), 3);
  assert_eq!(spans[0]["operation_name"], "early-op");
  assert_eq!(spans[1]["operation_name"], "mid-op");
  assert_eq!(spans[2]["operation_name"], "late-op");
}

#[tokio::test]
async fn get_test_spans_includes_exception_data() {
  let server = TestServer::start().await;
  server.seed_run("run-1", "my-app");
  server.seed_test("run-1", "test-exc", "exception test", "TracingSpec");
  server.seed_entry_full(
    "run-1",
    "test-exc",
    "HTTP",
    "POST /fail",
    "FAILED",
    "",
    "",
    "",
    "",
    "",
    "trace-exc",
  );
  server.seed_span_with_exception(
    "run-1",
    "trace-exc",
    "err-span",
    "",
    "POST /fail",
    "my-svc",
    "ERROR",
    "java.lang.NullPointerException",
    "Cannot invoke method on null",
    "at com.example.Service.process(Service.java:42)",
  );
  server.end_test_failed("run-1", "test-exc", 200, "NPE");

  let body = server.get_json("/runs/run-1/tests/test-exc/spans").await;
  let spans = body.as_array().unwrap();
  assert_eq!(spans.len(), 1);
  assert_eq!(spans[0]["status"], "ERROR");
  assert_eq!(spans[0]["exception_type"], "java.lang.NullPointerException");
  assert_eq!(
    spans[0]["exception_message"],
    "Cannot invoke method on null"
  );
  assert!(
    spans[0]["exception_stack_trace"]
      .as_str()
      .unwrap()
      .contains("Service.java:42")
  );
}

#[tokio::test]
async fn get_test_spans_isolates_between_tests() {
  let server = TestServer::start().await;
  server.seed_run("run-1", "my-app");
  server.seed_test("run-1", "t1", "test one", "Spec");
  server.seed_entry_full(
    "run-1", "t1", "HTTP", "GET /a", "PASSED", "", "", "", "", "", "trace-t1",
  );
  server.seed_span("run-1", "trace-t1", "s-t1", "", "GET /a", "svc");
  server.end_test("run-1", "t1", 100);
  server.seed_test("run-1", "t2", "test two", "Spec");
  server.seed_entry_full(
    "run-1", "t2", "HTTP", "GET /b", "PASSED", "", "", "", "", "", "trace-t2",
  );
  server.seed_span("run-1", "trace-t2", "s-t2", "", "GET /b", "svc");
  server.end_test("run-1", "t2", 100);
  server.end_run("run-1", 2, 0, 500);

  let t1_spans = server.get_json("/runs/run-1/tests/t1/spans").await;
  assert_eq!(t1_spans.as_array().unwrap().len(), 1);
  assert_eq!(t1_spans[0]["span_id"], "s-t1");

  let t2_spans = server.get_json("/runs/run-1/tests/t2/spans").await;
  assert_eq!(t2_spans.as_array().unwrap().len(), 1);
  assert_eq!(t2_spans[0]["span_id"], "s-t2");
}

// ---------------------------------------------------------------------------
// GET /api/v1/runs/:run_id/tests/:test_id/snapshots
// ---------------------------------------------------------------------------

#[tokio::test]
async fn get_snapshots_returns_snapshots_for_test() {
  let server = TestServer::start().await;
  server.seed_full_run();

  let body = server.get_json("/runs/run-1/tests/test-1/snapshots").await;
  let snapshots = body.as_array().unwrap();
  assert_eq!(snapshots.len(), 1);
  assert_eq!(snapshots[0]["system"], "Kafka");
  assert_eq!(snapshots[0]["summary"], "3 consumed, 1 published");
  assert_eq!(
    snapshots[0]["state_json"],
    r#"{"consumed":3,"published":1}"#
  );
}

#[tokio::test]
async fn get_snapshots_returns_empty_for_test_without_snapshots() {
  let server = TestServer::start().await;
  server.seed_full_run();

  let body = server.get_json("/runs/run-1/tests/test-2/snapshots").await;
  assert_eq!(body, Value::Array(vec![]));
}

#[tokio::test]
async fn get_snapshots_returns_multiple_systems() {
  let server = TestServer::start().await;
  server.seed_run("run-1", "my-app");
  server.seed_test("run-1", "test-snap", "snapshot test", "SnapshotSpec");
  server.seed_snapshot(
    "run-1",
    "test-snap",
    "Kafka",
    r#"{"consumed":5,"published":2}"#,
    "5 consumed, 2 published",
  );
  server.seed_snapshot(
    "run-1",
    "test-snap",
    "PostgreSQL",
    r#"{"rows_inserted":10}"#,
    "10 rows inserted",
  );
  server.seed_snapshot(
    "run-1",
    "test-snap",
    "Redis",
    r#"{"keys_set":3}"#,
    "3 keys set",
  );
  server.end_test("run-1", "test-snap", 300);

  let body = server
    .get_json("/runs/run-1/tests/test-snap/snapshots")
    .await;
  let snaps = body.as_array().unwrap();
  assert_eq!(snaps.len(), 3);
  let systems: Vec<&str> = snaps
    .iter()
    .map(|s| s["system"].as_str().unwrap())
    .collect();
  assert!(systems.contains(&"Kafka"));
  assert!(systems.contains(&"PostgreSQL"));
  assert!(systems.contains(&"Redis"));
}

#[tokio::test]
async fn get_snapshots_isolates_between_tests() {
  let server = TestServer::start().await;
  server.seed_run("run-1", "my-app");
  server.seed_test("run-1", "t1", "test one", "Spec");
  server.seed_snapshot("run-1", "t1", "Kafka", r#"{"consumed":1}"#, "1 consumed");
  server.end_test("run-1", "t1", 100);
  server.seed_test("run-1", "t2", "test two", "Spec");
  server.seed_snapshot("run-1", "t2", "Redis", r#"{"keys":5}"#, "5 keys");
  server.end_test("run-1", "t2", 100);
  server.end_run("run-1", 2, 0, 500);

  let t1_snaps = server.get_json("/runs/run-1/tests/t1/snapshots").await;
  let t1_arr = t1_snaps.as_array().unwrap();
  assert_eq!(t1_arr.len(), 1);
  assert_eq!(t1_arr[0]["system"], "Kafka");

  let t2_snaps = server.get_json("/runs/run-1/tests/t2/snapshots").await;
  let t2_arr = t2_snaps.as_array().unwrap();
  assert_eq!(t2_arr.len(), 1);
  assert_eq!(t2_arr[0]["system"], "Redis");
}

#[tokio::test]
async fn snapshot_state_json_preserves_complex_json() {
  let server = TestServer::start().await;
  server.seed_run("run-1", "my-app");
  server.seed_test("run-1", "test-json", "json test", "Spec");
  let complex_json = r#"{"messages":[{"topic":"orders","key":"k1","value":{"orderId":123}},{"topic":"orders","key":"k2","value":{"orderId":456}}],"count":2}"#;
  server.seed_snapshot("run-1", "test-json", "Kafka", complex_json, "2 messages");
  server.end_test("run-1", "test-json", 100);

  let body = server
    .get_json("/runs/run-1/tests/test-json/snapshots")
    .await;
  let snaps = body.as_array().unwrap();
  assert_eq!(snaps[0]["state_json"], complex_json);
}

// ---------------------------------------------------------------------------
// GET /api/v1/traces/:trace_id
// ---------------------------------------------------------------------------

#[tokio::test]
async fn get_trace_returns_all_spans_for_trace() {
  let server = TestServer::start().await;
  server.seed_full_run();

  let body = server.get_json("/traces/trace-abc").await;
  let spans = body.as_array().unwrap();
  assert_eq!(spans.len(), 2);
  assert_eq!(spans[0]["span_id"], "span-1");
  assert!(spans[0]["parent_span_id"].is_null());
  assert_eq!(spans[0]["service_name"], "product-api");
  assert_eq!(spans[1]["span_id"], "span-2");
  assert_eq!(spans[1]["parent_span_id"], "span-1");
  assert_eq!(spans[1]["service_name"], "product-db");
}

#[tokio::test]
async fn get_trace_returns_empty_for_unknown_trace() {
  let server = TestServer::start().await;

  let body = server.get_json("/traces/nonexistent").await;
  assert_eq!(body, Value::Array(vec![]));
}

// ---------------------------------------------------------------------------
// SSE endpoint
// ---------------------------------------------------------------------------

#[tokio::test]
async fn sse_endpoint_returns_200_with_event_stream_content_type() {
  let server = TestServer::start().await;

  let resp = server.get("/events/stream").await;
  assert_eq!(resp.status(), StatusCode::OK);
  let content_type = resp
    .headers()
    .get("content-type")
    .unwrap()
    .to_str()
    .unwrap();
  assert!(
    content_type.contains("text/event-stream"),
    "Expected text/event-stream, got: {content_type}",
  );
}

#[tokio::test]
async fn sse_stream_pushes_full_events_before_database_flush() {
  let server = TestServer::start().await;
  let service = Arc::new(PortalEventServiceImpl::new_with_ingest_config(
    server.repo.clone(),
    server.sse.clone(),
    50,
    Duration::from_secs(60),
  ));
  let mut resp = server.get("/events/stream").await;
  let mut buffer = String::new();

  assert_eq!(resp.status(), StatusCode::OK);

  send_event(
    service.as_ref(),
    run_started_event("run-live-sse", "live-sse-app", 1_704_067_200, 0),
  )
  .await
  .unwrap();
  send_event(
    service.as_ref(),
    test_started_event(
      "run-live-sse",
      "test-live",
      "streams before sqlite",
      "LiveSpec",
      1_704_067_201,
      0,
    ),
  )
  .await
  .unwrap();

  let first_event: Value = serde_json::from_str(&next_sse_data(&mut resp, &mut buffer).await.unwrap()).unwrap();
  assert_eq!(first_event["seq"], 1);
  assert_eq!(first_event["run_id"], "run-live-sse");
  assert_eq!(first_event["event_type"], "run_started");
  assert_eq!(first_event["payload"]["app_name"], "live-sse-app");

  let second_event: Value = serde_json::from_str(&next_sse_data(&mut resp, &mut buffer).await.unwrap()).unwrap();
  assert_eq!(second_event["seq"], 2);
  assert_eq!(second_event["event_type"], "test_started");
  assert_eq!(second_event["payload"]["test_id"], "test-live");
  assert_eq!(second_event["payload"]["status"], "RUNNING");

  let run_before_flush = server.get_json("/runs/run-live-sse").await;
  assert_eq!(run_before_flush, Value::Null);

  let tests_before_flush = server.get_json("/runs/run-live-sse/tests").await;
  assert_eq!(tests_before_flush, Value::Array(vec![]));

  flush_events(service.as_ref()).await;

  let run_after_flush = server.get_json("/runs/run-live-sse").await;
  assert_eq!(run_after_flush["status"], "RUNNING");

  let tests_after_flush = server.get_json("/runs/run-live-sse/tests").await;
  let tests_after_flush = tests_after_flush.as_array().unwrap();
  assert_eq!(tests_after_flush.len(), 1);
  assert_eq!(tests_after_flush[0]["id"], "test-live");
  assert_eq!(tests_after_flush[0]["status"], "RUNNING");
}

#[tokio::test]
async fn sse_broadcast_data_is_readable_after_notification() {
  // Simulates the real-world SSE flow: when the frontend receives an SSE
  // event and immediately refetches, the data must already be in the DB.
  //
  // The broadcast must fire AFTER the DB write, not before.
  let server = TestServer::start().await;
  let mut rx = server.sse.subscribe();

  // Seed a run via the repo so the FK is satisfied
  server.seed_run("run-sse", "sse-app");

  // Seed a test via the repo (bypasses gRPC, but simulates the write)
  server.seed_test("run-sse", "t-1", "my test", "Spec");

  // Broadcast an SSE event (simulates what process_event does after writing)
  server
    .sse
    .broadcast(r#"{"run_id":"run-sse","event_type":"test_started"}"#);

  // Subscriber receives the notification
  let msg = rx.try_recv().expect("should receive broadcast");
  assert!(msg.contains("run-sse"));

  // Immediately refetch — data must be present (this is what the browser does)
  let body = server.get_json("/runs/run-sse/tests").await;
  let tests = body.as_array().unwrap();
  assert_eq!(
    tests.len(),
    1,
    "Data must be readable when SSE notification arrives"
  );
}

#[tokio::test]
async fn sse_stream_sends_keep_alive() {
  // Without keep-alive, browsers and proxies close idle SSE connections
  // after 30-90 seconds, causing the UI to stop updating during long tests.
  //
  // With keep-alive enabled, the server sends a comment (`: keep-alive`)
  // periodically. We verify by reading the first chunk with a timeout.
  let server = TestServer::start().await;

  let mut resp = server.get("/events/stream").await;
  assert_eq!(resp.status(), StatusCode::OK);

  // Read the first chunk — with keep-alive, the server should send a
  // comment within the interval (15s). Without it, this times out.
  let result = tokio::time::timeout(std::time::Duration::from_secs(20), resp.chunk()).await;

  assert!(
    result.is_ok(),
    "SSE stream should send a keep-alive comment within 20 seconds"
  );
  let chunk = result.unwrap().unwrap();
  assert!(chunk.is_some(), "Keep-alive chunk should not be empty");
}

#[tokio::test]
async fn sse_stream_delivers_interleaved_notifications_for_concurrent_test_load() {
  let server = TestServer::start().await;
  let service = Arc::new(PortalEventServiceImpl::new(
    server.repo.clone(),
    server.sse.clone(),
  ));
  let mut resp = server.get("/events/stream").await;
  let mut buffer = String::new();
  let entry_count_per_test = 80usize;

  assert_eq!(resp.status(), StatusCode::OK);

  send_event(
    service.as_ref(),
    run_started_event("run-sse-load", "sse-load-app", 1_704_067_400, 0),
  )
  .await
  .unwrap();

  let service_a = service.clone();
  let service_b = service.clone();
  tokio::try_join!(
    async move {
      send_event(
        service_a.as_ref(),
        test_started_event(
          "run-sse-load",
          "test-a",
          "handles checkout",
          "ConcurrentSpec",
          1_704_067_401,
          0,
        ),
      )
      .await
    },
    async move {
      send_event(
        service_b.as_ref(),
        test_started_event(
          "run-sse-load",
          "test-b",
          "handles payment",
          "ConcurrentSpec",
          1_704_067_401,
          1,
        ),
      )
      .await
    },
  )
  .unwrap();

  let service_a = service.clone();
  let service_b = service.clone();
  tokio::try_join!(
    async move {
      for index in 0..entry_count_per_test {
        send_event(
          service_a.as_ref(),
          entry_recorded_event(
            "run-sse-load",
            "test-a",
            &format!("GET /checkout/{index}"),
            "PASSED",
            "trace-a",
            1_704_067_402 + index as i64,
            0,
          ),
        )
        .await?;
      }
      Ok::<(), tonic::Status>(())
    },
    async move {
      for index in 0..entry_count_per_test {
        send_event(
          service_b.as_ref(),
          entry_recorded_event(
            "run-sse-load",
            "test-b",
            &format!("POST /payment/{index}"),
            "FAILED",
            "trace-b",
            1_704_067_402 + index as i64,
            1,
          ),
        )
        .await?;
      }
      Ok::<(), tonic::Status>(())
    },
  )
  .unwrap();

  let service_a = service.clone();
  let service_b = service.clone();
  tokio::try_join!(
    async move {
      send_event(
        service_a.as_ref(),
        test_ended_event(
          "run-sse-load",
          "test-a",
          "PASSED",
          2_000,
          "",
          1_704_067_500,
          0,
        ),
      )
      .await
    },
    async move {
      send_event(
        service_b.as_ref(),
        test_ended_event(
          "run-sse-load",
          "test-b",
          "FAILED",
          2_200,
          "payment timeout",
          1_704_067_500,
          1,
        ),
      )
      .await
    },
  )
  .unwrap();

  send_event(
    service.as_ref(),
    run_ended_event("run-sse-load", 2, 1, 1, 4_200, 1_704_067_501, 0),
  )
  .await
  .unwrap();

  let expected_events = 1 + 2 + (entry_count_per_test * 2) + 2 + 1;
  let mut event_counts: HashMap<String, usize> = HashMap::new();

  for _ in 0..expected_events {
    let payload = next_sse_data(&mut resp, &mut buffer).await.unwrap();
    let event: Value = serde_json::from_str(&payload).unwrap();
    assert_eq!(event["run_id"], "run-sse-load");

    let event_type = event["event_type"]
      .as_str()
      .expect("event_type should be present")
      .to_string();
    *event_counts.entry(event_type).or_default() += 1;
  }

  assert_eq!(event_counts.get("run_started"), Some(&1));
  assert_eq!(event_counts.get("test_started"), Some(&2));
  assert_eq!(
    event_counts.get("entry_recorded"),
    Some(&(entry_count_per_test * 2))
  );
  assert_eq!(event_counts.get("test_ended"), Some(&2));
  assert_eq!(event_counts.get("run_ended"), Some(&1));

  flush_events(service.as_ref()).await;

  let tests = server.get_json("/runs/run-sse-load/tests").await;
  let tests = tests.as_array().unwrap();
  assert_eq!(tests.len(), 2);

  let test_a = tests.iter().find(|test| test["id"] == "test-a").unwrap();
  assert_eq!(test_a["status"], "PASSED");

  let test_b = tests.iter().find(|test| test["id"] == "test-b").unwrap();
  assert_eq!(test_b["status"], "FAILED");

  let entries_a = server
    .get_json("/runs/run-sse-load/tests/test-a/entries")
    .await;
  assert_eq!(entries_a.as_array().unwrap().len(), entry_count_per_test);

  let entries_b = server
    .get_json("/runs/run-sse-load/tests/test-b/entries")
    .await;
  assert_eq!(entries_b.as_array().unwrap().len(), entry_count_per_test);
}

// ---------------------------------------------------------------------------
// CORS headers
// ---------------------------------------------------------------------------

#[tokio::test]
async fn cors_headers_are_present() {
  let server = TestServer::start().await;

  let resp = server.get("/apps").await;
  assert!(
    resp.headers().contains_key("access-control-allow-origin"),
    "CORS header should be present"
  );
}

// ---------------------------------------------------------------------------
// Running status (in-progress run)
// ---------------------------------------------------------------------------

#[tokio::test]
async fn in_progress_run_has_running_status() {
  let server = TestServer::start().await;
  server.seed_run_at("run-live", "live-api", "2024-06-01T12:00:00Z", &["HTTP"]);

  let body = server.get_json("/runs/run-live").await;
  assert_eq!(body["status"], "RUNNING");
  assert!(body["ended_at"].is_null());
  assert!(body["duration_ms"].is_null());

  let apps = server.get_json("/apps").await;
  assert_eq!(apps[0]["latest_status"], "RUNNING");
}

// ---------------------------------------------------------------------------
// SPA fallback
// ---------------------------------------------------------------------------

#[tokio::test]
async fn spa_fallback_serves_index_html_for_unknown_paths() {
  let server = TestServer::start().await;

  let resp = server
    .client
    .get(format!("{}/some/frontend/route", server.base_url))
    .send()
    .await
    .unwrap();

  assert_ne!(
    resp.status(),
    StatusCode::METHOD_NOT_ALLOWED,
    "Should not return 405 for SPA routes"
  );
}

#[tokio::test]
async fn missing_spa_assets_return_404_instead_of_index_html() {
  let server = TestServer::start().await;

  let resp = server.get("/assets/does-not-exist.js").await;

  assert_eq!(resp.status(), StatusCode::NOT_FOUND);
}

// ---------------------------------------------------------------------------
// Multiple runs (ordering and latest-run logic)
// ---------------------------------------------------------------------------

#[tokio::test]
async fn runs_are_ordered_by_started_at_desc() {
  let server = TestServer::start().await;
  server.seed_run_at("run-old", "my-app", "2024-01-01T00:00:00Z", &[]);
  server.seed_run_at("run-new", "my-app", "2024-06-01T00:00:00Z", &[]);

  let body = server.get_json("/runs?app=my-app").await;
  let runs = body.as_array().unwrap();
  assert_eq!(runs.len(), 2);
  assert_eq!(runs[0]["id"], "run-new");
  assert_eq!(runs[1]["id"], "run-old");
}

#[tokio::test]
async fn runs_with_same_started_at_use_latest_inserted_as_tie_breaker() {
  let server = TestServer::start().await;
  server.seed_run_at("run-1", "my-app", "2024-06-01T00:00:00Z", &[]);
  server.seed_run_at("run-2", "my-app", "2024-06-01T00:00:00Z", &[]);

  let body = server.get_json("/runs?app=my-app").await;
  let runs = body.as_array().unwrap();

  assert_eq!(runs.len(), 2);
  assert_eq!(runs[0]["id"], "run-2");
  assert_eq!(runs[1]["id"], "run-1");
}

#[tokio::test]
async fn apps_returns_latest_run_id_for_multi_run_app() {
  let server = TestServer::start().await;
  server.seed_run_at("run-1", "my-app", "2024-01-01T00:00:00Z", &[]);
  server.seed_run_at("run-2", "my-app", "2024-06-01T00:00:00Z", &[]);

  let body = server.get_json("/apps").await;
  let apps = body.as_array().unwrap();
  assert_eq!(apps.len(), 1);
  assert_eq!(apps[0]["latest_run_id"], "run-2");
  assert_eq!(apps[0]["total_runs"], 2);
}

#[tokio::test]
async fn apps_does_not_duplicate_app_when_latest_runs_share_same_timestamp() {
  let server = TestServer::start().await;
  server.seed_run_at("run-1", "my-app", "2024-06-01T00:00:00Z", &[]);
  server.seed_run_at("run-2", "my-app", "2024-06-01T00:00:00Z", &[]);

  let body = server.get_json("/apps").await;
  let apps = body.as_array().unwrap();

  assert_eq!(
    apps.len(),
    1,
    "same app should appear only once in the sidebar"
  );
  assert_eq!(apps[0]["latest_run_id"], "run-2");
  assert_eq!(apps[0]["total_runs"], 2);
}
