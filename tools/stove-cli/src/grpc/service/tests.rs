use std::sync::Arc;
use std::time::Duration;

use super::DashboardEventServiceImpl;
use crate::proto;
use crate::sse::manager::SseManager;
use crate::storage::database::Database;
use crate::storage::repository::Repository;

fn test_service() -> DashboardEventServiceImpl {
  let db = Database::open(":memory:").unwrap();
  let repo = Arc::new(Repository::new(db));
  let sse = Arc::new(SseManager::new());
  DashboardEventServiceImpl::new_with_ingest_config(
    repo,
    sse,
    /*max_batch_size*/ 50,
    Duration::from_mins(1),
  )
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
  svc.flush_pending().await.unwrap();
  assert!(svc.repository.get_runs(None).unwrap().is_empty());
}

#[tokio::test]
async fn broadcast_fires_before_batch_flush() {
  let svc = test_service();
  let mut rx = svc.sse_manager.subscribe();

  svc
    .process_event(&proto::DashboardEvent {
      run_id: "run-1".to_string(),
      event: Some(proto::dashboard_event::Event::RunStarted(
        proto::RunStartedEvent {
          timestamp: Some(ts(1_704_067_200)),
          app_name: "my-api".to_string(),
          systems: vec!["HTTP".to_string()],
          stove_version: "0.23.1".to_string(),
        },
      )),
    })
    .unwrap();

  let msg = rx.try_recv().expect("broadcast should be sent on success");
  assert!(msg.contains("run_started"));
  assert!(
    svc.repository.get_runs(None).unwrap().is_empty(),
    "run should not be visible in SQLite before an explicit flush"
  );

  svc.flush_pending().await.unwrap();

  let runs = svc.repository.get_runs(None).unwrap();
  assert_eq!(runs.len(), 1);
}

#[tokio::test]
async fn process_run_started_event() {
  let svc = test_service();
  let event = proto::DashboardEvent {
    run_id: "run-1".to_string(),
    event: Some(proto::dashboard_event::Event::RunStarted(
      proto::RunStartedEvent {
        timestamp: Some(ts(1_704_067_200)),
        app_name: "product-api".to_string(),
        systems: vec!["HTTP".to_string(), "Kafka".to_string()],
        stove_version: "0.23.2".to_string(),
      },
    )),
  };

  svc.process_event(&event).unwrap();
  svc.flush_pending().await.unwrap();

  let runs = svc.repository.get_runs(None).unwrap();
  assert_eq!(runs.len(), 1);
  assert_eq!(runs[0].app_name, "product-api");
  assert_eq!(runs[0].stove_version.as_deref(), Some("0.23.2"));
}

#[tokio::test]
async fn process_full_lifecycle() {
  let svc = test_service();

  svc
    .process_event(&proto::DashboardEvent {
      run_id: "run-1".to_string(),
      event: Some(proto::dashboard_event::Event::RunStarted(
        proto::RunStartedEvent {
          timestamp: Some(ts(1_704_067_200)),
          app_name: "test-app".to_string(),
          stove_version: String::new(),
          systems: vec![],
        },
      )),
    })
    .unwrap();

  svc
    .process_event(&proto::DashboardEvent {
      run_id: "run-1".to_string(),
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

  svc
    .process_event(&proto::DashboardEvent {
      run_id: "run-1".to_string(),
      event: Some(proto::dashboard_event::Event::EntryRecorded(
        proto::EntryRecordedEvent {
          test_id: "test-1".to_string(),
          timestamp: Some(ts(1_704_067_202)),
          system: "HTTP".to_string(),
          action: "GET /api".to_string(),
          result: "PASSED".to_string(),
          input: String::new(),
          output: String::new(),
          metadata: std::collections::HashMap::default(),
          expected: String::new(),
          actual: String::new(),
          error: String::new(),
          trace_id: String::new(),
        },
      )),
    })
    .unwrap();

  svc
    .process_event(&proto::DashboardEvent {
      run_id: "run-1".to_string(),
      event: Some(proto::dashboard_event::Event::TestEnded(
        proto::TestEndedEvent {
          test_id: "test-1".to_string(),
          status: "PASSED".to_string(),
          duration_ms: 500,
          error: String::new(),
          timestamp: Some(ts(1_704_067_203)),
        },
      )),
    })
    .unwrap();

  svc
    .process_event(&proto::DashboardEvent {
      run_id: "run-1".to_string(),
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

  svc.flush_pending().await.unwrap();

  let runs = svc.repository.get_runs(None).unwrap();
  assert_eq!(runs.len(), 1);
  assert_eq!(runs[0].status, crate::storage::models::RunStatus::Passed);

  let tests = svc.repository.get_tests_for_run("run-1").unwrap();
  assert_eq!(tests.len(), 1);
  assert_eq!(tests[0].status, crate::storage::models::TestStatus::Passed);

  let entries = svc.repository.get_entries("run-1", "test-1").unwrap();
  assert_eq!(entries.len(), 1);
}
