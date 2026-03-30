//! Shared test infrastructure for e2e tests.
//!
//! Provides `TestServer` (a real axum server on a random port with in-memory SQLite)
//! and ergonomic seed helpers that hide `.unwrap()` noise and struct boilerplate.

use std::sync::Arc;

use serde_json::Value;
use stove::http::server::create_router;
use stove::sse::manager::SseManager;
use stove::storage::database::Database;
use stove::storage::models::{NewEntry, NewSpan};
use stove::storage::repository::Repository;

/// A running test server with its base URL and repository handle.
pub struct TestServer {
  pub base_url: String,
  pub repo: Arc<Repository>,
  pub sse: Arc<SseManager>,
  pub client: reqwest::Client,
}

impl TestServer {
  /// Start a test server on an OS-assigned port with an in-memory database.
  pub async fn start() -> Self {
    let db = Database::open(":memory:").expect("in-memory database should open");
    let repo = Arc::new(Repository::new(db));
    let sse_manager = Arc::new(SseManager::new());
    let router = create_router(repo.clone(), sse_manager.clone());

    let listener = tokio::net::TcpListener::bind("127.0.0.1:0")
      .await
      .expect("should bind to a free port");
    let port = listener.local_addr().unwrap().port();
    let base_url = format!("http://127.0.0.1:{port}");

    tokio::spawn(async move {
      axum::serve(listener, router).await.unwrap();
    });

    Self {
      base_url,
      repo,
      sse: sse_manager,
      client: reqwest::Client::new(),
    }
  }

  // ── HTTP helpers ──────────────────────────────────────────────────

  pub fn url(&self, path: &str) -> String {
    format!("{}/api/v1{path}", self.base_url)
  }

  pub async fn get(&self, path: &str) -> reqwest::Response {
    self
      .client
      .get(self.url(path))
      .send()
      .await
      .expect("request should succeed")
  }

  pub async fn get_json(&self, path: &str) -> Value {
    self
      .get(path)
      .await
      .json::<Value>()
      .await
      .expect("response should be valid JSON")
  }

  // ── Seed helpers ──────────────────────────────────────────────────
  //
  // Each helper wraps a repository call with sensible defaults and panics
  // on failure (tests should never hit DB errors with in-memory SQLite).

  /// Start a run (status = RUNNING until `end_run` is called).
  pub fn seed_run(&self, run_id: &str, app_name: &str) {
    self.seed_run_at(run_id, app_name, "2024-06-01T10:00:00Z", &[]);
  }

  /// Start a run with explicit timestamp and systems.
  pub fn seed_run_at(&self, run_id: &str, app_name: &str, started_at: &str, systems: &[&str]) {
    let systems: Vec<String> = systems.iter().map(|s| (*s).to_string()).collect();
    self
      .repo
      .save_run_start(run_id, app_name, started_at, &systems)
      .unwrap();
  }

  /// End a run with stats.
  pub fn end_run(&self, run_id: &str, passed: i32, failed: i32, duration_ms: i64) {
    self
      .repo
      .save_run_end(
        run_id,
        "2024-06-01T10:00:10Z",
        passed + failed,
        passed,
        failed,
        duration_ms,
      )
      .unwrap();
  }

  /// Start a test within a run.
  pub fn seed_test(&self, run_id: &str, test_id: &str, name: &str, spec: &str) {
    self
      .repo
      .save_test_start(run_id, test_id, name, spec, "2024-06-01T10:00:01Z")
      .unwrap();
  }

  /// End a test (pass). For failures, use `end_test_failed`.
  pub fn end_test(&self, run_id: &str, test_id: &str, duration_ms: i64) {
    self
      .repo
      .save_test_end(
        run_id,
        test_id,
        "PASSED",
        duration_ms,
        "",
        "2024-06-01T10:00:03Z",
      )
      .unwrap();
  }

  /// End a test with FAILED status and an error message.
  pub fn end_test_failed(&self, run_id: &str, test_id: &str, duration_ms: i64, error: &str) {
    self
      .repo
      .save_test_end(
        run_id,
        test_id,
        "FAILED",
        duration_ms,
        error,
        "2024-06-01T10:00:05Z",
      )
      .unwrap();
  }

  /// Save a test entry with only the important fields; the rest default to empty.
  pub fn seed_entry(&self, run_id: &str, test_id: &str, system: &str, action: &str, result: &str) {
    self.seed_entry_full(
      run_id, test_id, system, action, result, "", "", "", "", "", "",
    );
  }

  /// Save a test entry with all fields specified.
  #[allow(clippy::too_many_arguments)]
  pub fn seed_entry_full(
    &self,
    run_id: &str,
    test_id: &str,
    system: &str,
    action: &str,
    result: &str,
    input: &str,
    output: &str,
    expected: &str,
    actual: &str,
    error: &str,
    trace_id: &str,
  ) {
    self
      .repo
      .save_entry(&NewEntry {
        run_id: run_id.into(),
        test_id: test_id.into(),
        timestamp: "2024-06-01T10:00:02Z".into(),
        system: system.into(),
        action: action.into(),
        result: result.into(),
        input: input.into(),
        output: output.into(),
        metadata: "{}".into(),
        expected: expected.into(),
        actual: actual.into(),
        error: error.into(),
        trace_id: trace_id.into(),
      })
      .unwrap();
  }

  /// Save a span with only the key fields; the rest default to empty/zero.
  pub fn seed_span(
    &self,
    run_id: &str,
    trace_id: &str,
    span_id: &str,
    parent_span_id: &str,
    operation: &str,
    service: &str,
  ) {
    self
      .repo
      .save_span(&NewSpan {
        run_id: run_id.into(),
        trace_id: trace_id.into(),
        span_id: span_id.into(),
        parent_span_id: parent_span_id.into(),
        operation_name: operation.into(),
        service_name: service.into(),
        start_time_nanos: 1_000_000_000,
        end_time_nanos: 1_250_000_000,
        status: "OK".into(),
        attributes: "{}".into(),
        ..Default::default()
      })
      .unwrap();
  }

  /// Save a span with explicit timing (for ordering assertions).
  #[allow(clippy::too_many_arguments)]
  pub fn seed_span_timed(
    &self,
    run_id: &str,
    trace_id: &str,
    span_id: &str,
    parent_span_id: &str,
    operation: &str,
    service: &str,
    start_nanos: i64,
    end_nanos: i64,
    attributes: &str,
  ) {
    self
      .repo
      .save_span(&NewSpan {
        run_id: run_id.into(),
        trace_id: trace_id.into(),
        span_id: span_id.into(),
        parent_span_id: parent_span_id.into(),
        operation_name: operation.into(),
        service_name: service.into(),
        start_time_nanos: start_nanos,
        end_time_nanos: end_nanos,
        status: "OK".into(),
        attributes: attributes.into(),
        ..Default::default()
      })
      .unwrap();
  }

  /// Save a span with exception details.
  #[allow(clippy::too_many_arguments)]
  pub fn seed_span_with_exception(
    &self,
    run_id: &str,
    trace_id: &str,
    span_id: &str,
    parent_span_id: &str,
    operation: &str,
    service: &str,
    status: &str,
    exception_type: &str,
    exception_message: &str,
    exception_stack_trace: &str,
  ) {
    self
      .repo
      .save_span(&NewSpan {
        run_id: run_id.into(),
        trace_id: trace_id.into(),
        span_id: span_id.into(),
        parent_span_id: parent_span_id.into(),
        operation_name: operation.into(),
        service_name: service.into(),
        start_time_nanos: 1_000_000_000,
        end_time_nanos: 1_250_000_000,
        status: status.into(),
        attributes: "{}".into(),
        exception_type: exception_type.into(),
        exception_message: exception_message.into(),
        exception_stack_trace: exception_stack_trace.into(),
      })
      .unwrap();
  }

  /// Save a snapshot for a test.
  pub fn seed_snapshot(
    &self,
    run_id: &str,
    test_id: &str,
    system: &str,
    state_json: &str,
    summary: &str,
  ) {
    self
      .repo
      .save_snapshot(run_id, test_id, system, state_json, summary)
      .unwrap();
  }

  /// Seed a complete run with one passing test and one failing test.
  ///
  /// Creates: run-1 (product-api), test-1 (PASSED), test-2 (FAILED),
  /// entries for both, 2 spans with trace-abc, a Kafka snapshot on test-1.
  pub fn seed_full_run(&self) {
    self.seed_run_at(
      "run-1",
      "product-api",
      "2024-06-01T10:00:00Z",
      &["HTTP", "Kafka", "PostgreSQL"],
    );

    // Test 1: passing
    self.seed_test("run-1", "test-1", "should create product", "ProductSpec");
    self.seed_entry_full(
      "run-1",
      "test-1",
      "HTTP",
      "POST /products",
      "PASSED",
      r#"{"name":"widget"}"#,
      r#"{"id":42}"#,
      "",
      "",
      "",
      "trace-abc",
    );
    self.seed_span_timed(
      "run-1",
      "trace-abc",
      "span-1",
      "",
      "POST /products",
      "product-api",
      1_000_000_000,
      1_250_000_000,
      r#"{"http.method":"POST","http.status_code":"201"}"#,
    );
    self.seed_span_timed(
      "run-1",
      "trace-abc",
      "span-2",
      "span-1",
      "INSERT INTO products",
      "product-db",
      1_050_000_000,
      1_200_000_000,
      r#"{"db.system":"postgresql"}"#,
    );
    self.seed_snapshot(
      "run-1",
      "test-1",
      "Kafka",
      r#"{"consumed":3,"published":1}"#,
      "3 consumed, 1 published",
    );
    self.end_test("run-1", "test-1", 1500);

    // Test 2: failing
    self.seed_test("run-1", "test-2", "should reject duplicate", "ProductSpec");
    self.seed_entry_full(
      "run-1",
      "test-2",
      "HTTP",
      "POST /products",
      "FAILED",
      r#"{"name":"widget"}"#,
      "",
      "409 Conflict",
      "201 Created",
      "Expected conflict but got success",
      "",
    );
    self.end_test_failed("run-1", "test-2", 800, "Expected conflict but got success");

    // End run
    self.end_run("run-1", 1, 1, 10000);
  }
}
