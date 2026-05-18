use super::Repository;
use crate::storage::database::Database;
use crate::storage::models::AppSummary;
use crate::storage::models::Entry;
use crate::storage::models::NewEntry;
use crate::storage::models::NewSpan;
use crate::storage::models::Run;
use crate::storage::models::RunStatus;
use crate::storage::models::Snapshot;
use crate::storage::models::Span;
use crate::storage::models::Test;
use crate::storage::models::TestStatus;

fn test_repo() -> Repository {
  Repository::new(Database::open(":memory:").unwrap())
}

#[test]
fn full_event_lifecycle() {
  let repo = test_repo();

  repo
    .save_run_start_with_version(
      "run-1",
      "product-api",
      "2024-01-01T00:00:00Z",
      Some("0.23.2"),
      &["HTTP".into(), "Kafka".into()],
    )
    .unwrap();

  repo
    .save_test_start(
      "run-1",
      "test-1",
      "should create product",
      "ProductSpec",
      &[],
      "2024-01-01T00:00:01Z",
    )
    .unwrap();

  repo
    .save_entry(&NewEntry {
      run_id: "run-1".into(),
      test_id: "test-1".into(),
      timestamp: "2024-01-01T00:00:02Z".into(),
      system: "HTTP".into(),
      action: "POST /products".into(),
      result: "PASSED".into(),
      input: r#"{"name":"widget"}"#.into(),
      output: r#"{"id":1}"#.into(),
      metadata: "{}".into(),
      expected: String::new(),
      actual: String::new(),
      error: String::new(),
      trace_id: String::new(),
    })
    .unwrap();

  repo
    .save_span(&NewSpan {
      run_id: "run-1".into(),
      trace_id: "trace-abc".into(),
      span_id: "span-1".into(),
      operation_name: "POST /products".into(),
      service_name: "product-api".into(),
      start_time_nanos: 1_000_000_000,
      end_time_nanos: 1_100_000_000,
      status: "OK".into(),
      attributes: r#"{"http.method":"POST"}"#.into(),
      ..Default::default()
    })
    .unwrap();

  repo
    .save_snapshot(
      "run-1",
      "test-1",
      "Kafka",
      r#"{"consumed":5}"#,
      "5 messages consumed",
    )
    .unwrap();

  repo
    .save_test_end(
      "run-1",
      "test-1",
      "PASSED",
      1500,
      "",
      "2024-01-01T00:00:03Z",
    )
    .unwrap();

  repo
    .save_run_end("run-1", "2024-01-01T00:00:10Z", 1, 1, 0, 10000)
    .unwrap();

  let expected_run = Run {
    id: "run-1".into(),
    app_name: "product-api".into(),
    started_at: "2024-01-01T00:00:00Z".into(),
    ended_at: Some("2024-01-01T00:00:10Z".into()),
    status: RunStatus::Passed,
    total_tests: 1,
    passed: 1,
    failed: 0,
    duration_ms: Some(10000),
    stove_version: Some("0.23.2".into()),
    systems: vec!["HTTP".into(), "Kafka".into()],
  };
  assert_eq!(repo.get_runs(None).unwrap(), vec![expected_run.clone()]);
  assert_eq!(repo.get_run("run-1").unwrap(), Some(expected_run));

  assert_eq!(
    repo.get_tests_for_run("run-1").unwrap(),
    vec![Test {
      id: "test-1".into(),
      run_id: "run-1".into(),
      test_name: "should create product".into(),
      spec_name: "ProductSpec".into(),
      test_path: vec![],
      started_at: "2024-01-01T00:00:01Z".into(),
      ended_at: Some("2024-01-01T00:00:03Z".into()),
      status: TestStatus::Passed,
      duration_ms: Some(1500),
      error: None,
    }]
  );

  let entries = repo.get_entries("run-1", "test-1").unwrap();
  assert_eq!(
    entries,
    vec![Entry {
      id: entries[0].id,
      run_id: "run-1".into(),
      test_id: "test-1".into(),
      timestamp: "2024-01-01T00:00:02Z".into(),
      system: "HTTP".into(),
      action: "POST /products".into(),
      result: TestStatus::Passed,
      input: Some(r#"{"name":"widget"}"#.into()),
      output: Some(r#"{"id":1}"#.into()),
      metadata: Some("{}".into()),
      expected: None,
      actual: None,
      error: None,
      trace_id: None,
    }]
  );

  let trace = repo.get_trace("trace-abc").unwrap();
  assert_eq!(
    trace,
    vec![Span {
      id: trace[0].id,
      run_id: "run-1".into(),
      trace_id: "trace-abc".into(),
      span_id: "span-1".into(),
      parent_span_id: None,
      operation_name: "POST /products".into(),
      service_name: "product-api".into(),
      start_time_nanos: 1_000_000_000,
      end_time_nanos: 1_100_000_000,
      status: "OK".into(),
      attributes: Some(r#"{"http.method":"POST"}"#.into()),
      exception_type: None,
      exception_message: None,
      exception_stack_trace: None,
    }]
  );

  let snapshots = repo.get_snapshots("run-1", "test-1").unwrap();
  assert_eq!(
    snapshots,
    vec![Snapshot {
      id: snapshots[0].id,
      run_id: "run-1".into(),
      test_id: "test-1".into(),
      system: "Kafka".into(),
      state_json: r#"{"consumed":5}"#.into(),
      summary: "5 messages consumed".into(),
    }]
  );

  assert_eq!(
    repo.get_apps().unwrap(),
    vec![AppSummary {
      app_name: "product-api".into(),
      latest_run_id: "run-1".into(),
      latest_status: RunStatus::Passed,
      stove_version: Some("0.23.2".into()),
      total_runs: 1,
    }]
  );
}

#[test]
fn latest_app_version_comes_from_latest_run() {
  let repo = test_repo();
  repo
    .save_run_start_with_version(
      "run-1",
      "product-api",
      "2024-01-01T00:00:00Z",
      Some("0.23.0"),
      &[],
    )
    .unwrap();
  repo
    .save_run_start_with_version(
      "run-2",
      "product-api",
      "2024-01-02T00:00:00Z",
      Some("0.23.2"),
      &[],
    )
    .unwrap();

  assert_eq!(
    repo.get_apps().unwrap(),
    vec![AppSummary {
      app_name: "product-api".into(),
      latest_run_id: "run-2".into(),
      latest_status: RunStatus::Running,
      stove_version: Some("0.23.2".into()),
      total_runs: 2,
    }]
  );
}

#[test]
fn get_runs_filters_by_app_name() {
  let repo = test_repo();
  repo
    .save_run_start("run-1", "product-api", "2024-01-01T00:00:00Z", &[])
    .unwrap();
  repo
    .save_run_start("run-2", "order-api", "2024-01-01T00:00:01Z", &[])
    .unwrap();

  let product_runs = repo.get_runs(Some("product-api")).unwrap();
  assert_eq!(product_runs.len(), 1);
  assert_eq!(product_runs[0].app_name, "product-api");

  let all_runs = repo.get_runs(None).unwrap();
  assert_eq!(all_runs.len(), 2);
}

#[test]
fn clear_all_removes_everything() {
  let repo = test_repo();
  repo
    .save_run_start("run-1", "app", "2024-01-01T00:00:00Z", &[])
    .unwrap();
  repo
    .save_test_start("run-1", "test-1", "test", "", &[], "2024-01-01T00:00:01Z")
    .unwrap();

  repo.clear_all().unwrap();

  assert!(repo.get_runs(None).unwrap().is_empty());
  assert!(repo.get_tests_for_run("run-1").unwrap().is_empty());
}

#[test]
fn get_apps_returns_single_latest_run_when_started_at_ties() {
  let repo = test_repo();
  repo
    .save_run_start("run-1", "my-app", "2024-06-01T00:00:00Z", &[])
    .unwrap();
  repo
    .save_run_start("run-2", "my-app", "2024-06-01T00:00:00Z", &[])
    .unwrap();

  assert_eq!(
    repo.get_apps().unwrap(),
    vec![AppSummary {
      app_name: "my-app".into(),
      latest_run_id: "run-2".into(),
      latest_status: RunStatus::Running,
      stove_version: None,
      total_runs: 2,
    }]
  );
}

#[test]
fn get_runs_orders_same_timestamp_runs_by_latest_inserted_first() {
  let repo = test_repo();
  repo
    .save_run_start("run-1", "my-app", "2024-06-01T00:00:00Z", &[])
    .unwrap();
  repo
    .save_run_start("run-2", "my-app", "2024-06-01T00:00:00Z", &[])
    .unwrap();

  let runs = repo.get_runs(Some("my-app")).unwrap();

  assert_eq!(runs.len(), 2);
  assert_eq!(runs[0].id, "run-2");
  assert_eq!(runs[1].id, "run-1");
}

#[test]
fn get_spans_for_test_does_not_cross_match_similar_test_ids() {
  let repo = test_repo();
  repo
    .save_run_start("run-1", "my-app", "2024-06-01T00:00:00Z", &[])
    .unwrap();
  repo
    .save_test_start(
      "run-1",
      "test-1",
      "first test",
      "Spec",
      &[],
      "2024-06-01T00:00:01Z",
    )
    .unwrap();
  repo
    .save_test_start(
      "run-1",
      "test-10",
      "tenth test",
      "Spec",
      &[],
      "2024-06-01T00:00:02Z",
    )
    .unwrap();
  repo
    .save_span(&NewSpan {
      run_id: "run-1".into(),
      trace_id: "trace-10".into(),
      span_id: "span-10".into(),
      operation_name: "GET /ten".into(),
      service_name: "my-app".into(),
      start_time_nanos: 1_000_000_000,
      end_time_nanos: 1_100_000_000,
      status: "OK".into(),
      attributes: r#"{"x-stove-test-id":"test-10"}"#.into(),
      ..Default::default()
    })
    .unwrap();

  let spans = repo.get_spans_for_test("run-1", "test-1").unwrap();

  assert!(spans.is_empty());
}
