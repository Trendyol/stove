use crate::ingest::{EventIngestor, tests::test_service};

use crate::proto::{self, dashboard_event::Event};
use crate::storage::models::RunStatus;

const RUN_ID: &str = "environment-run";

fn event(sequence: u64, payload: Event) -> proto::DashboardEvent {
  proto::DashboardEvent {
    run_id: RUN_ID.into(),
    event_id: format!("environment-{sequence}"),
    sequence,
    event: Some(payload),
  }
}

fn environment(status: &str) -> proto::RunEnvironmentEvent {
  proto::RunEnvironmentEvent {
    components: vec![proto::RunComponent {
      id: "postgres-analytics".into(),
      name: "PostgreSQL".into(),
      key: "analytics".into(),
      r#type: "PostgresqlSystem".into(),
      role: "SYSTEM".into(),
      phases: vec![proto::ComponentPhase {
        name: "START".into(),
        status: status.into(),
        started_at: "2026-11-17T00:00:00Z".into(),
        duration_ms: 123,
        error: String::new(),
      }],
    }],
  }
}

#[test]
fn environment_is_durable_idempotent_and_updates_without_test_activity() {
  let service = started_service();
  let initial = event(2, Event::RunEnvironment(environment("RUNNING")));
  assert!(!service.ingest(&initial).unwrap().duplicate);
  assert!(service.ingest(&initial).unwrap().duplicate);
  service
    .ingest(&event(3, Event::RunEnvironment(environment("SUCCEEDED"))))
    .unwrap();
  let repository = &service.repository;
  let persisted = repository.get_run_environment(RUN_ID).unwrap().unwrap();
  assert_eq!(persisted, environment("SUCCEEDED"));
  let live = service.repository.live_events_after(0, 10).unwrap();
  assert_eq!(live.len(), 3);
  let last: serde_json::Value = serde_json::from_str(&live.last().unwrap().json).unwrap();
  assert_eq!(last["payload"]["components"][0]["key"], "analytics");
  assert_eq!(
    last["payload"]["components"][0]["phases"][0]["status"],
    "SUCCEEDED"
  );
  repository.clear_all().unwrap();
  assert!(repository.get_run_environment(RUN_ID).unwrap().is_none());
}

#[test]
fn lifecycle_failure_fails_the_run_without_inventing_failed_tests() {
  let service = started_service();
  service
    .ingest(&event(
      2,
      Event::RunEnded(proto::RunEndedEvent {
        lifecycle_failed: true,
        ..Default::default()
      }),
    ))
    .unwrap();
  let run = service.repository.get_run(RUN_ID).unwrap().unwrap();
  assert_eq!(run.status, RunStatus::Failed);
  assert_eq!(run.total_tests, 0);
  assert_eq!(run.failed, 0);
  let live = service.repository.live_events_after(1, 10).unwrap();
  let last: serde_json::Value = serde_json::from_str(&live[0].json).unwrap();
  assert_eq!(last["payload"]["status"], "FAILED");
}

#[test]
fn rejects_invalid_or_orphaned_environment_and_legacy_runs_have_none() {
  let service = test_service();
  assert!(
    service
      .ingest(&event(1, Event::RunEnvironment(environment("RUNNING"))))
      .is_err()
  );
  let service = started_service();
  let repository = &service.repository;
  assert!(repository.get_run_environment(RUN_ID).unwrap().is_none());
  assert!(
    service
      .ingest(&event(2, Event::RunEnvironment(environment("INVENTED"))))
      .is_err()
  );
  let mut duplicate = environment("RUNNING");
  duplicate.components.push(duplicate.components[0].clone());
  assert!(
    service
      .ingest(&event(2, Event::RunEnvironment(duplicate)))
      .is_err()
  );
}

fn started_service() -> EventIngestor {
  let service = test_service();
  service
    .ingest(&event(
      1,
      Event::RunStarted(proto::RunStartedEvent::default()),
    ))
    .unwrap();
  service
}
