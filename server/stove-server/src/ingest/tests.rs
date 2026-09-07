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
  let svc = test_service();
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

#[test]
fn every_live_variant_matches_its_wire_envelope_storage_tag_and_schema() {
  use proto::dashboard_event::Event;

  let service = test_service();
  let cases = [
    (
      Event::RunStarted(proto::RunStartedEvent::default()),
      "run_started",
    ),
    (
      Event::RunEnded(proto::RunEndedEvent::default()),
      "run_ended",
    ),
    (
      Event::TestStarted(proto::TestStartedEvent::default()),
      "test_started",
    ),
    (
      Event::TestEnded(proto::TestEndedEvent {
        status: "PASSED".into(),
        ..Default::default()
      }),
      "test_ended",
    ),
    (
      Event::EntryRecorded(proto::EntryRecordedEvent {
        result: "PASSED".into(),
        ..Default::default()
      }),
      "entry_recorded",
    ),
    (
      Event::SpanRecorded(proto::SpanRecordedEvent {
        status: "UNSET".into(),
        ..Default::default()
      }),
      "span_recorded",
    ),
    (Event::Snapshot(proto::SnapshotEvent::default()), "snapshot"),
    (
      Event::MockInteraction(proto::MockInteractionEvent::default()),
      "mock_interaction",
    ),
    (
      Event::MockWarning(proto::MockWarningEvent::default()),
      "mock_warning",
    ),
  ];

  let document = serde_json::to_value(crate::http::openapi_document()).unwrap();
  let schemas = &document["components"]["schemas"];
  let variants = schemas["LiveDashboardPayload"]["oneOf"].as_array().unwrap();
  assert_eq!(variants.len(), cases.len());

  for (payload, expected_tag) in cases {
    let prepared = service
      .prepare_event(&proto::DashboardEvent {
        run_id: "run-wire".to_string(),
        event: Some(payload),
        ..Default::default()
      })
      .unwrap();
    let live = prepared.live.with_seq(42);
    let json = serde_json::to_value(&live).unwrap();

    assert_eq!(json.as_object().unwrap().len(), 4);
    assert_eq!(json["seq"], 42);
    assert_eq!(json["run_id"], "run-wire");
    assert_eq!(json["event_type"], expected_tag);
    assert_eq!(live.event_type(), expected_tag);
    let variant = variants
      .iter()
      .find(|variant| variant["properties"]["event_type"]["enum"][0] == expected_tag)
      .unwrap();
    let payload_ref = variant["properties"]["payload"]["$ref"].as_str().unwrap();
    let payload_schema = document
      .pointer(payload_ref.strip_prefix('#').unwrap())
      .unwrap();
    let fields = json["payload"].as_object().unwrap();
    let required = payload_schema["required"].as_array().unwrap();
    assert_eq!(
      payload_schema["properties"].as_object().unwrap().len(),
      fields.len()
    );
    assert_eq!(required.len(), fields.len());
    for (field, value) in fields {
      assert!(
        required.iter().any(|name| name == field),
        "{expected_tag}.{field}"
      );
      if value.is_null() {
        assert!(
          payload_schema["properties"][field]["type"]
            .as_array()
            .unwrap()
            .iter()
            .any(|kind| kind == "null"),
          "{expected_tag}.{field} must allow null"
        );
      }
    }
    if let Some(id) = json["payload"].get("id") {
      assert_eq!(id, -42);
    }
  }
}

#[tokio::test]
async fn invalid_statuses_never_commit_domain_or_live_events() {
  use proto::dashboard_event::Event;
  let svc = test_service();
  svc
    .repository
    .save_run_start("run-1", "app", "2026-09-07T10:00:00Z", &[])
    .unwrap();
  svc
    .repository
    .save_test_start(
      "run-1",
      "test-1",
      "test",
      "spec",
      &[],
      "2026-09-07T10:00:00Z",
    )
    .unwrap();
  let events = [
    Event::TestEnded(proto::TestEndedEvent {
      test_id: "test-1".into(),
      status: "UNKNOWN".into(),
      ..Default::default()
    }),
    Event::EntryRecorded(proto::EntryRecordedEvent {
      test_id: "test-1".into(),
      result: "UNKNOWN".into(),
      ..Default::default()
    }),
    Event::SpanRecorded(proto::SpanRecordedEvent {
      status: "PASSED".into(),
      ..Default::default()
    }),
  ];
  for event in events {
    let result = svc.ingest(&proto::DashboardEvent {
      run_id: "run-1".into(),
      event: Some(event),
      ..Default::default()
    });
    assert!(matches!(
      result,
      Err(crate::error::AppError::InvalidEvent(_))
    ));
  }
  assert_eq!(svc.repository.latest_live_event_id().unwrap(), 0);
  assert_eq!(
    svc.repository.get_tests_for_run("run-1").unwrap()[0].status,
    crate::storage::models::TestStatus::Running
  );
  assert!(
    svc
      .repository
      .get_entries("run-1", "test-1")
      .unwrap()
      .is_empty()
  );
}
