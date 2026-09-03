use std::sync::Arc;

use super::EventIngestor;
use crate::proto;
use crate::sse::manager::SseManager;
use crate::storage::repository::Repository;

fn test_service() -> EventIngestor {
  let repo = Arc::new(Repository::connect_sqlite(":memory:", 1).unwrap());
  let sse = Arc::new(SseManager::new());
  EventIngestor::new(repo, sse)
}

fn ts(seconds: i64) -> prost_types::Timestamp {
  prost_types::Timestamp { seconds, nanos: 0 }
}

#[tokio::test]
async fn no_broadcast_on_invalid_event_order() {
  let svc = test_service();
  let mut rx = svc.sse_manager.subscribe();

  let result = svc.process_event(&proto::DashboardEvent {
    run_id: "nonexistent-run".to_string(),
    event_id: String::new(),
    sequence: 0,
    event: Some(proto::dashboard_event::Event::TestStarted(
      proto::TestStartedEvent {
        test_id: "t-1".to_string(),
        test_name: "orphan test".to_string(),
        spec_name: "Spec".to_string(),
        timestamp: Some(ts(1_704_067_200)),
        test_path: vec![],
      },
    )),
  });

  assert!(result.is_err(), "invalid event ordering should be rejected");
  assert!(
    rx.try_recv().is_err(),
    "invalid events must not be broadcast"
  );
  assert!(svc.repository.get_runs(None).unwrap().is_empty());
  assert!(svc.repository.get_runs(None).unwrap().is_empty());
}

#[tokio::test]
async fn acknowledgement_requires_a_committed_domain_and_outbox_event() {
  let svc = test_service();

  svc
    .process_event(&proto::DashboardEvent {
      run_id: "run-1".to_string(),
      event_id: String::new(),
      sequence: 0,
      event: Some(proto::dashboard_event::Event::RunStarted(
        proto::RunStartedEvent {
          timestamp: Some(ts(1_704_067_200)),
          app_name: "my-api".to_string(),
          systems: vec!["HTTP".to_string()],
          stove_version: "0.23.1".to_string(),
          metadata: std::collections::HashMap::new(),
        },
      )),
    })
    .unwrap();

  let runs = svc.repository.get_runs(None).unwrap();
  assert_eq!(runs.len(), 1);
  assert_eq!(svc.repository.latest_live_event_id().unwrap(), 1);
}

#[tokio::test]
async fn process_run_started_event() {
  let svc = test_service();
  let event = proto::DashboardEvent {
    run_id: "run-1".to_string(),
    event_id: String::new(),
    sequence: 0,
    event: Some(proto::dashboard_event::Event::RunStarted(
      proto::RunStartedEvent {
        timestamp: Some(ts(1_704_067_200)),
        app_name: "product-api".to_string(),
        systems: vec!["HTTP".to_string(), "Kafka".to_string()],
        stove_version: "0.23.2".to_string(),
        metadata: [("team".to_string(), "checkout".to_string())].into(),
      },
    )),
  };

  svc.process_event(&event).unwrap();

  let runs = svc.repository.get_runs(None).unwrap();
  assert_eq!(runs.len(), 1);
  assert_eq!(runs[0].app_name, "product-api");
  assert_eq!(runs[0].stove_version.as_deref(), Some("0.23.2"));
  assert_eq!(
    runs[0].metadata.get("team").map(String::as_str),
    Some("checkout")
  );
}

#[tokio::test]
#[allow(clippy::too_many_lines)]
async fn process_full_lifecycle() {
  let svc = test_service();

  svc
    .process_event(&proto::DashboardEvent {
      run_id: "run-1".to_string(),
      event_id: String::new(),
      sequence: 0,
      event: Some(proto::dashboard_event::Event::RunStarted(
        proto::RunStartedEvent {
          timestamp: Some(ts(1_704_067_200)),
          app_name: "test-app".to_string(),
          stove_version: String::new(),
          systems: vec![],
          metadata: std::collections::HashMap::new(),
        },
      )),
    })
    .unwrap();

  svc
    .process_event(&proto::DashboardEvent {
      run_id: "run-1".to_string(),
      event_id: String::new(),
      sequence: 0,
      event: Some(proto::dashboard_event::Event::TestStarted(
        proto::TestStartedEvent {
          test_id: "test-1".to_string(),
          test_name: "my test".to_string(),
          spec_name: "MySpec".to_string(),
          timestamp: Some(ts(1_704_067_201)),
          test_path: vec![],
        },
      )),
    })
    .unwrap();

  for attempt in 1_i64..=5 {
    let failed = attempt < 5;
    svc
      .process_event(&proto::DashboardEvent {
        run_id: "run-1".to_string(),
        event_id: String::new(),
        sequence: 0,
        event: Some(proto::dashboard_event::Event::EntryRecorded(
          proto::EntryRecordedEvent {
            test_id: "test-1".to_string(),
            timestamp: Some(ts(1_704_067_201 + attempt)),
            system: "HTTP".to_string(),
            action: "GET /api".to_string(),
            result: if failed { "FAILED" } else { "PASSED" }.to_string(),
            input: String::new(),
            output: String::new(),
            metadata: std::collections::HashMap::default(),
            expected: "200".to_string(),
            actual: if failed { "503" } else { "200" }.to_string(),
            error: if failed {
              format!("not ready on attempt {attempt}")
            } else {
              String::new()
            },
            trace_id: String::new(),
          },
        )),
      })
      .unwrap();
  }

  svc
    .process_event(&proto::DashboardEvent {
      run_id: "run-1".to_string(),
      event_id: String::new(),
      sequence: 0,
      event: Some(proto::dashboard_event::Event::TestEnded(
        proto::TestEndedEvent {
          test_id: "test-1".to_string(),
          status: "PASSED".to_string(),
          duration_ms: 500,
          error: String::new(),
          timestamp: Some(ts(1_704_067_207)),
        },
      )),
    })
    .unwrap();

  svc
    .process_event(&proto::DashboardEvent {
      run_id: "run-1".to_string(),
      event_id: String::new(),
      sequence: 0,
      event: Some(proto::dashboard_event::Event::RunEnded(
        proto::RunEndedEvent {
          timestamp: Some(ts(1_704_067_210)),
          total_tests: 1,
          passed: 1,
          failed: 0,
          duration_ms: 10000,
        },
      )),
    })
    .unwrap();

  let runs = svc.repository.get_runs(None).unwrap();
  assert_eq!(runs.len(), 1);
  assert_eq!(runs[0].status, crate::storage::models::RunStatus::Passed);

  let tests = svc.repository.get_tests_for_run("run-1").unwrap();
  assert_eq!(tests.len(), 1);
  assert_eq!(tests[0].status, crate::storage::models::TestStatus::Passed);

  let entries = svc.repository.get_entries("run-1", "test-1").unwrap();
  assert_eq!(entries.len(), 1);
  assert_eq!(
    entries[0].result,
    crate::storage::models::TestStatus::Passed
  );
  assert_eq!(entries[0].attempt_count, 5);
  assert_eq!(entries[0].failure_count, 4);
  assert_eq!(entries[0].actual.as_deref(), Some("200"));
}

#[tokio::test]
async fn assertions_with_distinct_expectations_do_not_share_retry_identity() {
  let svc = test_service();

  svc
    .process_event(&proto::DashboardEvent {
      run_id: "run-expectations".to_string(),
      event_id: String::new(),
      sequence: 0,
      event: Some(proto::dashboard_event::Event::RunStarted(
        proto::RunStartedEvent {
          timestamp: Some(ts(1_704_067_200)),
          app_name: "test-app".to_string(),
          systems: vec!["HTTP".to_string()],
          stove_version: String::new(),
          metadata: std::collections::HashMap::new(),
        },
      )),
    })
    .unwrap();
  svc
    .process_event(&proto::DashboardEvent {
      run_id: "run-expectations".to_string(),
      event_id: String::new(),
      sequence: 0,
      event: Some(proto::dashboard_event::Event::TestStarted(
        proto::TestStartedEvent {
          test_id: "test-expectations".to_string(),
          test_name: "checks two statuses".to_string(),
          spec_name: "ExpectationSpec".to_string(),
          timestamp: Some(ts(1_704_067_201)),
          test_path: vec![],
        },
      )),
    })
    .unwrap();

  for (offset, expected) in ["200", "201"].into_iter().enumerate() {
    svc
      .process_event(&proto::DashboardEvent {
        run_id: "run-expectations".to_string(),
        event_id: String::new(),
        sequence: 0,
        event: Some(proto::dashboard_event::Event::EntryRecorded(
          proto::EntryRecordedEvent {
            test_id: "test-expectations".to_string(),
            timestamp: Some(ts(1_704_067_202 + i64::try_from(offset).unwrap())),
            system: "HTTP".to_string(),
            action: "GET /api".to_string(),
            result: "FAILED".to_string(),
            input: String::new(),
            output: String::new(),
            metadata: std::collections::HashMap::default(),
            expected: expected.to_string(),
            actual: "503".to_string(),
            error: format!("expected {expected}"),
            trace_id: String::new(),
          },
        )),
      })
      .unwrap();
  }

  let entries = svc
    .repository
    .get_entries("run-expectations", "test-expectations")
    .unwrap();
  assert_eq!(entries.len(), 2);
  assert_ne!(entries[0].assertion_id, entries[1].assertion_id);
}
