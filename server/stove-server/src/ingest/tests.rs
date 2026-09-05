use std::sync::Arc;
use std::time::{Duration, Instant};

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

#[tokio::test(flavor = "multi_thread", worker_threads = 1)]
async fn sqlite_ingestion_waiting_for_the_writer_does_not_starve_async_work() {
  let svc = test_service();
  let locked_repository = svc.repository.clone();
  let (locked_tx, locked_rx) = std::sync::mpsc::sync_channel(1);
  let blocker = std::thread::spawn(move || {
    locked_repository.with_write_db_locked(|| {
      locked_tx.send(()).unwrap();
      std::thread::sleep(Duration::from_millis(300));
    });
  });
  locked_rx.recv().unwrap();

  let ingest = tokio::spawn(async move {
    svc.ingest(&proto::DashboardEvent {
      run_id: "run-responsive".to_string(),
      event_id: "event-responsive".to_string(),
      sequence: 1,
      event: Some(proto::dashboard_event::Event::RunStarted(
        proto::RunStartedEvent {
          timestamp: Some(ts(1_704_067_200)),
          app_name: "responsive-app".to_string(),
          ..Default::default()
        },
      )),
    })
  });

  let started = Instant::now();
  tokio::time::sleep(Duration::from_millis(50)).await;
  assert!(
    started.elapsed() < Duration::from_millis(200),
    "a synchronous SQLite lock wait starved the single Tokio worker for {:?}",
    started.elapsed()
  );

  blocker.join().unwrap();
  ingest.await.unwrap().unwrap();
}

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn acknowledgement_does_not_wait_for_the_sse_read_lane() {
  let directory = tempfile::tempdir().unwrap();
  let path = directory.path().join("independent-lanes.db");
  let repository = Arc::new(Repository::connect_sqlite(path.to_str().unwrap(), 1).unwrap());
  let svc = EventIngestor::new(repository, Arc::new(SseManager::new()));
  let locked_repository = svc.repository.clone();
  let (locked_tx, locked_rx) = std::sync::mpsc::sync_channel(1);
  let (release_tx, release_rx) = std::sync::mpsc::sync_channel(1);
  let blocker = std::thread::spawn(move || {
    locked_repository.with_read_db_locked(|| {
      locked_tx.send(()).unwrap();
      release_rx.recv().unwrap();
    });
  });
  locked_rx.recv().unwrap();

  let acknowledgement = tokio::time::timeout(Duration::from_millis(250), async move {
    svc.ingest(&proto::DashboardEvent {
      run_id: "run-independent-ack".to_string(),
      event_id: "event-independent-ack".to_string(),
      sequence: 1,
      event: Some(proto::dashboard_event::Event::RunStarted(
        proto::RunStartedEvent {
          timestamp: Some(ts(1_704_067_200)),
          app_name: "responsive-app".to_string(),
          ..Default::default()
        },
      )),
    })
  })
  .await
  .expect("the ACK must not wait for the SSE relay's read connection")
  .unwrap();

  assert!(acknowledgement.accepted);
  release_tx.send(()).unwrap();
  blocker.join().unwrap();
}

#[tokio::test]
async fn no_broadcast_on_invalid_event_order() {
  let svc = test_service();
  let mut rx = svc.sse_manager.subscribe();

  let result = svc.ingest(&proto::DashboardEvent {
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

  let acknowledgement = svc
    .ingest(&proto::DashboardEvent {
      run_id: "run-1".to_string(),
      event_id: "event-1".to_string(),
      sequence: 1,
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

  assert_eq!(
    acknowledgement,
    proto::EventAck {
      accepted: true,
      event_id: "event-1".to_string(),
      sequence: 1,
      duplicate: false,
    }
  );
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

  svc.ingest(&event).unwrap();

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
    .ingest(&proto::DashboardEvent {
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
    .ingest(&proto::DashboardEvent {
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
      .ingest(&proto::DashboardEvent {
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
    .ingest(&proto::DashboardEvent {
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
    .ingest(&proto::DashboardEvent {
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
    .ingest(&proto::DashboardEvent {
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
    .ingest(&proto::DashboardEvent {
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
      .ingest(&proto::DashboardEvent {
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

#[tokio::test]
async fn admission_is_shared_and_rejects_before_scheduling_work() {
  let repository = Arc::new(Repository::connect_sqlite(":memory:", 0).unwrap());
  let svc = EventIngestor::with_capacity(repository, Arc::new(SseManager::new()), 1);
  let permit = svc.admission.clone().acquire_owned().await.unwrap();
  let result = svc
    .clone()
    .ingest_async(proto::DashboardEvent::default())
    .await;
  assert!(matches!(result, Err(crate::error::AppError::Overloaded)));
  drop(permit);
  let result = svc.ingest_async(proto::DashboardEvent::default()).await;
  assert!(matches!(
    result,
    Err(crate::error::AppError::InvalidEvent(_))
  ));
  assert_eq!(svc.admission.available_permits(), 1);
}

#[tokio::test]
async fn cancelled_request_keeps_admission_until_blocking_work_finishes() {
  let repository = Arc::new(Repository::connect_sqlite(":memory:", 0).unwrap());
  let svc = EventIngestor::with_capacity(repository.clone(), Arc::new(SseManager::new()), 1);
  let (locked_tx, locked_rx) = tokio::sync::oneshot::channel();
  let (release_tx, release_rx) = std::sync::mpsc::channel();
  let blocker = std::thread::spawn(move || {
    repository.with_write_db_locked(|| {
      locked_tx.send(()).unwrap();
      release_rx.recv().unwrap();
    });
  });
  locked_rx.await.unwrap();
  let worker = svc.clone();
  let request = tokio::spawn(async move {
    worker
      .ingest_async(proto::DashboardEvent {
        run_id: "cancelled-run".into(),
        event_id: "cancelled-event".into(),
        sequence: 1,
        event: Some(proto::dashboard_event::Event::RunStarted(
          proto::RunStartedEvent {
            app_name: "cancelled".into(),
            timestamp: Some(ts(1_704_067_200)),
            ..Default::default()
          },
        )),
      })
      .await
  });
  while svc.admission.available_permits() != 0 {
    tokio::task::yield_now().await;
  }
  request.abort();
  let _ = request.await;
  assert_eq!(svc.admission.available_permits(), 0);
  release_tx.send(()).unwrap();
  tokio::time::timeout(Duration::from_secs(5), async {
    while svc.admission.available_permits() == 0 {
      tokio::time::sleep(Duration::from_millis(5)).await;
    }
  })
  .await
  .unwrap();
  blocker.join().unwrap();
  assert_eq!(svc.repository.get_runs(None).unwrap().len(), 1);
}

fn batch_run_event(sequence: u64) -> proto::DashboardEvent {
  proto::DashboardEvent {
    run_id: "batch-run".into(),
    event_id: format!("batch-{sequence}"),
    sequence,
    event: Some(proto::dashboard_event::Event::RunStarted(
      proto::RunStartedEvent {
        app_name: "batch-app".into(),
        timestamp: Some(ts(1_704_067_200)),
        ..Default::default()
      },
    )),
  }
}

#[tokio::test]
async fn batch_rolls_back_domain_inbox_and_publication_on_sequence_failure() {
  let svc = test_service();
  let result = svc
    .ingest_batch(proto::DashboardEventBatch {
      events: vec![batch_run_event(1), batch_run_event(3)],
    })
    .await;
  assert!(matches!(
    result,
    Err(crate::error::AppError::InvalidEvent(_))
  ));
  assert!(svc.repository.get_runs(None).unwrap().is_empty());
  assert_eq!(svc.repository.latest_live_event_id().unwrap(), 0);
  let batch = proto::DashboardEventBatch {
    events: vec![batch_run_event(1), batch_run_event(2)],
  };
  let ack = svc.ingest_batch(batch.clone()).await.unwrap();
  assert_eq!(ack.acknowledgements.len(), 2);
  assert!(
    ack
      .acknowledgements
      .iter()
      .all(|ack| ack.accepted && !ack.duplicate)
  );
  let replay = svc.ingest_batch(batch).await.unwrap();
  assert!(
    replay
      .acknowledgements
      .iter()
      .all(|ack| ack.accepted && ack.duplicate)
  );
  assert_eq!(svc.repository.latest_live_event_id().unwrap(), 2);
}

#[tokio::test]
async fn batch_rejects_mixed_runs_and_capacity_limits_without_writes() {
  let svc = test_service();
  let mut other = batch_run_event(2);
  other.run_id = "other-run".into();
  for events in [
    vec![],
    vec![batch_run_event(1), other],
    (1..=101).map(batch_run_event).collect(),
  ] {
    assert!(matches!(
      svc
        .ingest_batch(proto::DashboardEventBatch { events })
        .await,
      Err(crate::error::AppError::InvalidEvent(_))
    ));
  }
  let mut oversized = batch_run_event(1);
  oversized.run_id = "x".repeat(1024 * 1024);
  assert!(matches!(
    svc
      .ingest_batch(proto::DashboardEventBatch {
        events: vec![oversized]
      })
      .await,
    Err(crate::error::AppError::InvalidEvent(_))
  ));
  assert!(svc.repository.get_runs(None).unwrap().is_empty());
}

#[tokio::test]
async fn batch_correlates_assertion_attempts_against_earlier_writes_in_the_transaction() {
  let svc = test_service();
  let started = proto::DashboardEvent {
    run_id: "batch-run".into(),
    event_id: "test-started".into(),
    sequence: 2,
    event: Some(proto::dashboard_event::Event::TestStarted(
      proto::TestStartedEvent {
        test_id: "test".into(),
        test_name: "test".into(),
        timestamp: Some(ts(1_704_067_200)),
        ..Default::default()
      },
    )),
  };
  let mut events = vec![batch_run_event(1), started];
  for sequence in 3..=5 {
    events.push(proto::DashboardEvent {
      run_id: "batch-run".into(),
      event_id: format!("attempt-{sequence}"),
      sequence,
      event: Some(proto::dashboard_event::Event::EntryRecorded(
        proto::EntryRecordedEvent {
          test_id: "test".into(),
          timestamp: Some(ts(1_704_067_200)),
          system: "HTTP".into(),
          action: "GET /api".into(),
          result: if sequence == 5 { "PASSED" } else { "FAILED" }.into(),
          expected: "200".into(),
          ..Default::default()
        },
      )),
    });
  }
  svc
    .ingest_batch(proto::DashboardEventBatch { events })
    .await
    .unwrap();
  let entries = svc.repository.get_entries("batch-run", "test").unwrap();
  assert_eq!(entries.len(), 1);
  assert_eq!(entries[0].attempt_count, 3);
  assert_eq!(entries[0].failure_count, 2);
}
