use super::Repository;
use crate::storage::models::AppSummary;
use crate::storage::models::Entry;
use crate::storage::models::NewEntry;
use crate::storage::models::NewMockInteraction;
use crate::storage::models::NewMockWarning;
use crate::storage::models::NewSpan;
use crate::storage::models::Run;
use crate::storage::models::RunStatus;
use crate::storage::models::Snapshot;
use crate::storage::models::Span;
use crate::storage::models::Test;
use crate::storage::models::TestStatus;
use diesel::connection::SimpleConnection;
use diesel::prelude::*;
use diesel::sql_types::BigInt;

#[derive(QueryableByName)]
struct CountRow {
  #[diesel(sql_type = BigInt)]
  count: i64,
}

fn test_repo() -> Repository {
  Repository::connect_sqlite(":memory:", 1).unwrap()
}

fn test_repo_with_retention(retention_runs_per_app: usize) -> Repository {
  Repository::connect_sqlite(":memory:", retention_runs_per_app).unwrap()
}

#[test]
#[allow(clippy::too_many_lines)]
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
      assertion_id: "assertion-post-products".into(),
      correlation_key: String::new(),
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
    metadata: std::collections::BTreeMap::new(),
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
      assertion_id: "assertion-post-products".into(),
      attempt_count: 1,
      failure_count: 0,
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
      captured_at: None,
      trigger: "TEST_END".into(),
    }]
  );

  assert_eq!(
    repo.get_apps().unwrap(),
    vec![AppSummary {
      app_name: "product-api".into(),
      latest_run_id: "run-1".into(),
      latest_status: RunStatus::Passed,
      stove_version: Some("0.23.2".into()),
      metadata: std::collections::BTreeMap::new(),
    }]
  );
}

#[test]
fn entries_return_latest_assertion_attempt_with_retry_counts() {
  let repo = test_repo();
  repo
    .save_run_start("run-1", "product-api", "2024-01-01T00:00:00Z", &[])
    .unwrap();
  repo
    .save_test_start(
      "run-1",
      "test-1",
      "eventually creates product",
      "ProductSpec",
      &[],
      "2024-01-01T00:00:01Z",
    )
    .unwrap();

  for attempt in 1..=5 {
    let failed = attempt < 5;
    repo
      .save_entry(&NewEntry {
        run_id: "run-1".into(),
        test_id: "test-1".into(),
        timestamp: format!("2024-01-01T00:00:0{}Z", attempt + 1),
        system: "PostgreSQL".into(),
        action: "Query".into(),
        result: if failed { "FAILED" } else { "PASSED" }.into(),
        input: "select * from products".into(),
        output: String::new(),
        metadata: "{}".into(),
        expected: "one row".into(),
        actual: if failed { "no rows" } else { "one row" }.into(),
        error: if failed {
          format!("not ready on attempt {attempt}")
        } else {
          String::new()
        },
        trace_id: String::new(),
        assertion_id: "assertion-query-products".into(),
        correlation_key: String::new(),
      })
      .unwrap();
  }

  let raw_entries = repo.get_raw_entries("run-1", "test-1").unwrap();
  assert_eq!(
    raw_entries.len(),
    5,
    "every attempt must remain available in the audit log"
  );
  assert_eq!(raw_entries[0].failure_count, 1);
  assert_eq!(raw_entries[4].failure_count, 0);

  let entries = repo.get_entries("run-1", "test-1").unwrap();
  assert_eq!(entries.len(), 1);
  assert_eq!(entries[0].result, TestStatus::Passed);
  assert_eq!(entries[0].attempt_count, 5);
  assert_eq!(entries[0].failure_count, 4);
  assert_eq!(entries[0].actual.as_deref(), Some("one row"));
  assert_eq!(entries[0].error, None);
}

#[test]
#[allow(clippy::too_many_lines)]
fn mock_interactions_and_warnings_roundtrip() {
  let repo = test_repo();

  repo
    .save_mock_interaction(&NewMockInteraction {
      run_id: "run-1".into(),
      test_id: Some("test-1".into()),
      timestamp: "2024-01-01T00:00:02Z".into(),
      system: "WireMock".into(),
      protocol: "HTTP".into(),
      method: "POST".into(),
      target: "/payments".into(),
      matched: true,
      stub_id: Some("stub-1".into()),
      attribution: "PROVEN_STUB".into(),
      request_body: r#"{"amount":100}"#.into(),
      response_body: r#"{"ok":true}"#.into(),
      status: "200".into(),
      latency_ms: Some(12),
      near_misses: "[]".into(),
      scenario_name: Some("payment retry".into()),
      scenario_state: Some("attempt-2".into()),
      next_scenario_state: Some("recovered".into()),
      configured_delay_ms: Some(250),
      fault: Some("CONNECTION_RESET_BY_PEER".into()),
      client_deadline_ms: Some(500),
      ..Default::default()
    })
    .unwrap();

  // Unattributed evidence keeps test_id NULL — the run-level lane.
  repo
    .save_mock_interaction(&NewMockInteraction {
      run_id: "run-1".into(),
      test_id: None,
      timestamp: "2024-01-01T00:00:03Z".into(),
      system: "gRPC Mock".into(),
      protocol: "GRPC".into(),
      target: "users.UserService/GetUser".into(),
      matched: false,
      attribution: "UNATTRIBUTED".into(),
      status: "UNIMPLEMENTED".into(),
      near_misses: r#"["no stubs registered for this method"]"#.into(),
      ..Default::default()
    })
    .unwrap();

  repo
    .save_mock_warning(&NewMockWarning {
      run_id: "run-1".into(),
      test_id: Some("test-1".into()),
      timestamp: "2024-01-01T00:00:04Z".into(),
      system: "WireMock".into(),
      kind: "UNUSED_STUB".into(),
      message: "Stub GET /never was registered by this test but never matched.".into(),
      stub_id: Some("stub-2".into()),
      target: Some("GET /never".into()),
    })
    .unwrap();

  repo
    .save_mock_warning(&NewMockWarning {
      run_id: "run-1".into(),
      test_id: None,
      timestamp: "2024-01-01T00:00:05Z".into(),
      system: "gRPC Mock".into(),
      kind: "UNVALIDATED_UNMATCHED".into(),
      message: "Unattributed warning.".into(),
      stub_id: None,
      target: Some("users.UserService/GetUser".into()),
    })
    .unwrap();

  let test_interactions = repo
    .get_mock_interactions_for_test("run-1", "test-1")
    .unwrap();
  assert_eq!(test_interactions.len(), 1);
  assert_eq!(test_interactions[0].target, "/payments");
  assert!(test_interactions[0].matched);
  assert_eq!(test_interactions[0].attribution, "PROVEN_STUB");
  assert_eq!(test_interactions[0].latency_ms, Some(12));
  assert!(test_interactions[0].near_misses.is_empty());
  assert_eq!(
    test_interactions[0].scenario_name.as_deref(),
    Some("payment retry")
  );
  assert_eq!(test_interactions[0].configured_delay_ms, Some(250));
  assert_eq!(
    test_interactions[0].fault.as_deref(),
    Some("CONNECTION_RESET_BY_PEER")
  );

  let run_interactions = repo.get_mock_interactions_for_run("run-1").unwrap();
  assert_eq!(run_interactions.len(), 2);
  let unattributed = run_interactions
    .iter()
    .find(|interaction| interaction.test_id.is_none())
    .unwrap();
  assert_eq!(unattributed.attribution, "UNATTRIBUTED");
  assert_eq!(unattributed.status, "UNIMPLEMENTED");
  assert_eq!(
    unattributed.near_misses,
    vec!["no stubs registered for this method"]
  );
  let ambient_interactions = repo
    .get_unattributed_mock_interactions_for_run("run-1")
    .unwrap();
  assert_eq!(ambient_interactions.len(), 1);
  assert!(ambient_interactions[0].test_id.is_none());

  let warnings = repo.get_mock_warnings_for_test("run-1", "test-1").unwrap();
  assert_eq!(warnings.len(), 1);
  assert_eq!(warnings[0].kind, "UNUSED_STUB");
  assert_eq!(repo.get_mock_warnings_for_run("run-1").unwrap().len(), 2);
  let ambient_warnings = repo
    .get_unattributed_mock_warnings_for_run("run-1")
    .unwrap();
  assert_eq!(ambient_warnings.len(), 1);
  assert!(ambient_warnings[0].test_id.is_none());

  repo.clear_all().unwrap();
  assert!(
    repo
      .get_mock_interactions_for_run("run-1")
      .unwrap()
      .is_empty()
  );
  assert!(repo.get_mock_warnings_for_run("run-1").unwrap().is_empty());
}

#[test]
fn malformed_rows_are_reported_instead_of_dropped() {
  let repo = test_repo();
  repo
    .save_mock_interaction(&NewMockInteraction {
      run_id: "run-1".into(),
      test_id: Some("test-1".into()),
      timestamp: "2024-01-01T00:00:02Z".into(),
      system: "WireMock".into(),
      protocol: "HTTP".into(),
      method: "GET".into(),
      target: "/broken".into(),
      matched: false,
      attribution: "PROVEN_HEADER".into(),
      status: "404".into(),
      near_misses: "[]".into(),
      ..Default::default()
    })
    .unwrap();
  repo
    .lock_write_db()
    .conn()
    .batch_execute("UPDATE mock_interactions SET near_misses = 'not-json' WHERE run_id = 'run-1'")
    .unwrap();

  assert!(
    repo
      .get_mock_interactions_for_test("run-1", "test-1")
      .is_err()
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
      metadata: std::collections::BTreeMap::new(),
    }]
  );
  assert!(repo.get_run("run-1").unwrap().is_some());
}

#[test]
#[allow(clippy::too_many_lines)]
fn ending_a_run_prunes_previous_completed_results_for_that_app() {
  let repo = test_repo();
  repo
    .save_run_start("old-run", "product-api", "2024-01-01T00:00:00Z", &[])
    .unwrap();
  repo
    .save_test_start(
      "old-run",
      "old-test",
      "old test",
      "ProductSpec",
      &[],
      "2024-01-01T00:00:01Z",
    )
    .unwrap();
  repo
    .save_entry(&NewEntry {
      run_id: "old-run".into(),
      test_id: "old-test".into(),
      timestamp: "2024-01-01T00:00:02Z".into(),
      system: "HTTP".into(),
      action: "GET /products".into(),
      result: "PASSED".into(),
      input: String::new(),
      output: String::new(),
      metadata: String::new(),
      expected: String::new(),
      actual: String::new(),
      error: String::new(),
      trace_id: "old-trace".into(),
      assertion_id: "old-assertion".into(),
      correlation_key: String::new(),
    })
    .unwrap();
  repo
    .save_span(&NewSpan {
      run_id: "old-run".into(),
      trace_id: "old-trace".into(),
      span_id: "old-span".into(),
      operation_name: "GET /products".into(),
      service_name: "product-api".into(),
      ..Default::default()
    })
    .unwrap();
  repo
    .save_snapshot("old-run", "old-test", "PostgreSQL", "{}", "old state")
    .unwrap();
  repo
    .save_mock_interaction(&NewMockInteraction {
      run_id: "old-run".into(),
      test_id: Some("old-test".into()),
      timestamp: "2024-01-01T00:00:03Z".into(),
      system: "WireMock".into(),
      protocol: "HTTP".into(),
      method: "GET".into(),
      target: "/products".into(),
      attribution: "PROVEN_STUB".into(),
      status: "200".into(),
      near_misses: "[]".into(),
      ..Default::default()
    })
    .unwrap();
  repo
    .save_mock_warning(&NewMockWarning {
      run_id: "old-run".into(),
      test_id: Some("old-test".into()),
      timestamp: "2024-01-01T00:00:04Z".into(),
      system: "WireMock".into(),
      kind: "UNUSED_STUB".into(),
      message: "old warning".into(),
      stub_id: None,
      target: None,
    })
    .unwrap();
  repo
    .save_run_end("old-run", "2024-01-01T00:00:05Z", 1, 1, 0, 5000)
    .unwrap();

  repo
    .save_run_start("other-run", "order-api", "2024-01-01T00:00:06Z", &[])
    .unwrap();
  repo
    .save_run_start("new-run", "product-api", "2024-01-01T00:00:07Z", &[])
    .unwrap();

  assert!(repo.get_run("old-run").unwrap().is_some());

  repo
    .save_run_end("new-run", "2024-01-01T00:00:08Z", 0, 0, 0, 1000)
    .unwrap();

  assert!(repo.get_run("old-run").unwrap().is_none());
  assert!(repo.get_tests_for_run("old-run").unwrap().is_empty());
  assert!(repo.get_run("new-run").unwrap().is_some());
  assert!(repo.get_run("other-run").unwrap().is_some());

  let mut db = repo.lock_write_db();
  for table in [
    "entries",
    "spans",
    "snapshots",
    "mock_interactions",
    "mock_warnings",
  ] {
    let count = diesel::sql_query(format!(
      "SELECT COUNT(*) AS count FROM {table} WHERE run_id = 'old-run'"
    ))
    .get_result::<CountRow>(db.conn())
    .unwrap()
    .count;
    assert_eq!(count, 0, "{table} should not retain old app results");
  }
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
fn get_apps_returns_only_the_new_run_when_started_at_ties() {
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
      metadata: std::collections::BTreeMap::new(),
    }]
  );
}

#[test]
fn get_runs_keeps_overlapping_runs_when_started_at_ties() {
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
fn configurable_retention_keeps_the_latest_completed_runs() {
  let repo = test_repo_with_retention(2);

  for index in 1..=3 {
    let run_id = format!("run-{index}");
    repo
      .save_run_start(
        &run_id,
        "my-app",
        &format!("2024-06-0{index}T00:00:00Z"),
        &[],
      )
      .unwrap();
    repo
      .save_run_end(
        &run_id,
        &format!("2024-06-0{index}T00:01:00Z"),
        0,
        0,
        0,
        60_000,
      )
      .unwrap();
  }

  let runs = repo.get_runs(Some("my-app")).unwrap();
  assert_eq!(
    runs.iter().map(|run| run.id.as_str()).collect::<Vec<_>>(),
    vec!["run-3", "run-2"]
  );
}

#[test]
fn retention_never_prunes_an_active_run() {
  let repo = test_repo_with_retention(1);

  repo
    .save_run_start("old-run", "my-app", "2024-06-01T00:00:00Z", &[])
    .unwrap();
  repo
    .save_run_end("old-run", "2024-06-01T00:01:00Z", 0, 0, 0, 60_000)
    .unwrap();
  repo
    .save_run_start("active-run", "my-app", "2024-06-02T00:00:00Z", &[])
    .unwrap();
  repo
    .save_run_start("new-run", "my-app", "2024-06-03T00:00:00Z", &[])
    .unwrap();
  repo
    .save_run_end("new-run", "2024-06-03T00:01:00Z", 0, 0, 0, 60_000)
    .unwrap();

  assert!(repo.get_run("old-run").unwrap().is_none());
  assert_eq!(
    repo
      .get_runs(Some("my-app"))
      .unwrap()
      .iter()
      .map(|run| run.id.as_str())
      .collect::<Vec<_>>(),
    vec!["new-run", "active-run"]
  );
}

#[test]
fn an_older_run_finishing_last_does_not_evict_a_newer_run() {
  let repo = test_repo_with_retention(1);

  repo
    .save_run_start("old-run", "my-app", "2024-06-01T00:00:00Z", &[])
    .unwrap();
  repo
    .save_run_start("new-run", "my-app", "2024-06-02T00:00:00Z", &[])
    .unwrap();
  repo
    .save_run_end("new-run", "2024-06-02T00:01:00Z", 0, 0, 0, 60_000)
    .unwrap();
  repo
    .save_run_end("old-run", "2024-06-03T00:01:00Z", 0, 0, 0, 60_000)
    .unwrap();

  assert!(repo.get_run("old-run").unwrap().is_none());
  assert!(repo.get_run("new-run").unwrap().is_some());
}

#[test]
fn ending_an_unknown_run_remains_a_no_op() {
  let repo = test_repo();

  repo
    .save_run_end("unknown-run", "2024-06-01T00:01:00Z", 0, 0, 0, 60_000)
    .unwrap();

  assert!(repo.get_runs(None).unwrap().is_empty());
}

#[test]
fn zero_retention_disables_automatic_pruning() {
  let repo = test_repo_with_retention(0);

  for index in 1..=2 {
    let run_id = format!("run-{index}");
    repo
      .save_run_start(
        &run_id,
        "my-app",
        &format!("2024-06-0{index}T00:00:00Z"),
        &[],
      )
      .unwrap();
    repo
      .save_run_end(
        &run_id,
        &format!("2024-06-0{index}T00:01:00Z"),
        0,
        0,
        0,
        60_000,
      )
      .unwrap();
  }

  assert_eq!(repo.get_runs(Some("my-app")).unwrap().len(), 2);
}

#[test]
fn retention_can_be_changed_while_the_repository_is_running() {
  let repo = test_repo_with_retention(2);
  assert_eq!(repo.retention_runs_per_app(), 2);

  repo.set_retention_runs_per_app(1);
  assert_eq!(repo.retention_runs_per_app(), 1);

  for index in 1..=2 {
    let run_id = format!("run-{index}");
    repo
      .save_run_start(
        &run_id,
        "my-app",
        &format!("2024-06-0{index}T00:00:00Z"),
        &[],
      )
      .unwrap();
    repo
      .save_run_end(
        &run_id,
        &format!("2024-06-0{index}T00:01:00Z"),
        0,
        0,
        0,
        60_000,
      )
      .unwrap();
  }

  let runs = repo.get_runs(Some("my-app")).unwrap();
  assert_eq!(runs.len(), 1);
  assert_eq!(runs[0].id, "run-2");
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

#[test]
fn sqlite_database_explorer_discovers_schema_and_executes_crud() {
  let repo = test_repo_with_retention(0);
  let schema = repo.database_schema().unwrap();
  let runs = schema
    .tables
    .iter()
    .find(|table| table.name == "runs")
    .expect("runs table should be discoverable");
  assert!(
    runs
      .columns
      .iter()
      .any(|column| column.name == "id" && column.primary_key)
  );
  assert!(
    runs
      .columns
      .iter()
      .any(|column| column.name == "metadata" && !column.nullable)
  );

  let inserted = repo
    .execute_database_query(
      "INSERT INTO runs (id, app_name, started_at) \
       VALUES ('explorer-run', 'before', '2024-06-01T00:00:00Z')",
      100,
    )
    .unwrap();
  assert_eq!(inserted.affected_rows, 1);
  let selected = repo
    .execute_database_query(
      "SELECT id, app_name FROM runs WHERE id = 'explorer-run'",
      100,
    )
    .unwrap();
  assert_eq!(selected.columns, ["id", "app_name"]);
  assert_eq!(
    selected.rows,
    vec![vec![Some("explorer-run".into()), Some("before".into())]]
  );

  let updated = repo
    .execute_database_query(
      "UPDATE runs SET app_name = 'after' WHERE id = 'explorer-run'",
      100,
    )
    .unwrap();
  assert_eq!(updated.affected_rows, 1);
  assert_eq!(
    repo.get_run("explorer-run").unwrap().unwrap().app_name,
    "after"
  );

  let deleted = repo
    .execute_database_query("DELETE FROM runs WHERE id = 'explorer-run'", 100)
    .unwrap();
  assert_eq!(deleted.affected_rows, 1);
  assert!(repo.get_run("explorer-run").unwrap().is_none());
}

#[test]
fn sqlite_database_explorer_caps_results_and_rejects_multiple_statements() {
  let repo = test_repo();
  let result = repo
    .execute_database_query("SELECT 1 AS value UNION ALL SELECT 2", 1)
    .unwrap();
  assert_eq!(result.rows, vec![vec![Some("1".into())]]);
  assert!(result.truncated);

  assert!(
    repo
      .execute_database_query("SELECT 1; SELECT 2", 100)
      .is_err()
  );
}
