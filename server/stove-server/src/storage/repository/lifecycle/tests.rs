use crate::{
  ingest::EventIngestor,
  proto,
  sse::manager::SseManager,
  storage::{
    models::{RunStatus, TestStatus},
    repository::Repository,
  },
};
use std::sync::Arc;

fn event(sequence: u64, inner: proto::dashboard_event::Event) -> proto::DashboardEvent {
  proto::DashboardEvent {
    run_id: "idle-run".into(),
    event_id: format!("idle-event-{sequence}"),
    sequence,
    event: Some(inner),
  }
}
fn start() -> proto::DashboardEvent {
  event(
    1,
    proto::dashboard_event::Event::RunStarted(proto::RunStartedEvent {
      app_name: "idle-app".into(),
      timestamp: Some(prost_types::Timestamp {
        seconds: 1,
        nanos: 0,
      }),
      ..Default::default()
    }),
  )
}
fn test_start(sequence: u64, id: &str) -> proto::DashboardEvent {
  event(
    sequence,
    proto::dashboard_event::Event::TestStarted(proto::TestStartedEvent {
      test_id: id.into(),
      test_name: id.into(),
      ..Default::default()
    }),
  )
}
fn age(repo: &Repository) {
  repo
    .execute_database_query(
      "UPDATE runs SET last_activity_at = '2000-01-01T00:00:00.000Z' WHERE id = 'idle-run'",
      1,
    )
    .unwrap();
}

pub(in crate::storage::repository) fn lifecycle_round_trip(repo: &Arc<Repository>) {
  repo.update_retention(0).unwrap();
  assert_eq!(repo.storage_stats().unwrap().run_idle_timeout_seconds, 3600);
  repo.initialize_idle_timeout(3600).unwrap();
  let ingestor = EventIngestor::new(repo.clone(), Arc::new(SseManager::new()));
  let start = start();
  ingestor.ingest(&start).unwrap();
  ingestor.ingest(&test_start(2, "unfinished")).unwrap();
  ingestor.ingest(&test_start(3, "finished")).unwrap();
  ingestor
    .ingest(&event(
      4,
      proto::dashboard_event::Event::TestEnded(proto::TestEndedEvent {
        test_id: "finished".into(),
        status: "PASSED".into(),
        ..Default::default()
      }),
    ))
    .unwrap();
  // Producer timestamps are ancient, but receipt time is fresh.
  assert_eq!(repo.expire_idle_runs().unwrap(), 0);
  age(repo);
  repo.update_idle_timeout(0).unwrap();
  assert_eq!(repo.expire_idle_runs().unwrap(), 0);
  repo.initialize_idle_timeout(3600).unwrap();
  assert_eq!(repo.storage_stats().unwrap().run_idle_timeout_seconds, 0);
  repo.update_idle_timeout(3600).unwrap();
  // Duplicates and invalid sequence numbers cannot keep an abandoned producer alive.
  assert!(ingestor.ingest(&start).unwrap().duplicate);
  assert!(ingestor.ingest(&test_start(99, "invalid")).is_err());
  assert_eq!(repo.expire_idle_runs().unwrap(), 1);
  assert_eq!(repo.expire_idle_runs().unwrap(), 0);
  assert_abandoned_evidence(repo);
  assert!(ingestor.ingest(&start).unwrap().duplicate);
  assert_eq!(
    repo.get_run("idle-run").unwrap().unwrap().status,
    RunStatus::Abandoned
  );
  // The server event did not consume producer sequence 5.
  ingestor.ingest(&test_start(5, "resumed")).unwrap();
  let restored = repo.get_run("idle-run").unwrap().unwrap();
  assert_eq!(restored.status, RunStatus::Running);
  assert!(restored.abandoned_at.is_none());
  assert_eq!(
    repo
      .get_test("idle-run", "unfinished")
      .unwrap()
      .unwrap()
      .status,
    TestStatus::Running
  );
  assert_eq!(
    repo
      .get_test("idle-run", "finished")
      .unwrap()
      .unwrap()
      .status,
    TestStatus::Passed
  );
  assert_eq!(
    repo
      .purge_runs(&["idle-run".into()], false)
      .unwrap()
      .purged_runs,
    0
  );
  age(repo);
  assert_eq!(repo.expire_idle_runs().unwrap(), 1);
  ingestor
    .ingest(&event(
      6,
      proto::dashboard_event::Event::RunEnded(proto::RunEndedEvent {
        total_tests: 3,
        passed: 1,
        ..Default::default()
      }),
    ))
    .unwrap();
  assert_eq!(
    repo.get_run("idle-run").unwrap().unwrap().status,
    RunStatus::Passed
  );
  assert_eq!(
    repo
      .get_test("idle-run", "unfinished")
      .unwrap()
      .unwrap()
      .status,
    TestStatus::Abandoned
  );
  assert_eq!(repo.expire_idle_runs().unwrap(), 0);
  assert_eq!(
    repo
      .purge_runs(&["idle-run".into()], false)
      .unwrap()
      .purged_runs,
    1
  );
}

#[test]
fn sqlite_idle_run_lifecycle() {
  lifecycle_round_trip(&Arc::new(
    Repository::connect_sqlite(":memory:", 0).unwrap(),
  ));
}

#[test]
fn sqlite_idle_settings_survive_restart() {
  let dir = tempfile::tempdir().unwrap();
  let path = dir.path().join("idle.db");
  let repo = Repository::connect_sqlite(path.to_str().unwrap(), 0).unwrap();
  repo.initialize_idle_timeout(3600).unwrap();
  repo.update_idle_timeout(1800).unwrap();
  assert!(repo.update_idle_timeout(u64::MAX).is_err());
  drop(repo);
  let repo = Repository::connect_sqlite(path.to_str().unwrap(), 0).unwrap();
  repo.initialize_idle_timeout(3600).unwrap();
  assert_eq!(repo.storage_stats().unwrap().run_idle_timeout_seconds, 1800);
}

#[test]
fn sqlite_retention_includes_abandoned_history() {
  let repo = Arc::new(Repository::connect_sqlite(":memory:", 1).unwrap());
  let ingestor = EventIngestor::new(repo.clone(), Arc::new(SseManager::new()));
  ingestor.ingest(&start()).unwrap();
  age(&repo);
  assert_eq!(repo.expire_idle_runs().unwrap(), 1);
  repo
    .save_run_start("new", "idle-app", "2026-10-22T00:00:00Z", &[])
    .unwrap();
  repo
    .save_run_end("new", "2026-10-22T00:00:10Z", 1, 1, 0, 10000)
    .unwrap();
  assert!(repo.get_run("idle-run").unwrap().is_none());
}

#[test]
fn late_completion_outside_retention_commits_before_later_pruning() {
  let repo = Arc::new(Repository::connect_sqlite(":memory:", 1).unwrap());
  let ingestor = EventIngestor::new(repo.clone(), Arc::new(SseManager::new()));
  ingestor.ingest(&start()).unwrap();
  repo
    .save_run_start("new", "idle-app", "2026-10-22T00:00:00Z", &[])
    .unwrap();
  repo
    .save_run_end("new", "2026-10-22T00:00:10Z", 1, 1, 0, 10000)
    .unwrap();
  age(&repo);
  assert_eq!(repo.expire_idle_runs().unwrap(), 1);
  let ended = event(
    2,
    proto::dashboard_event::Event::RunEnded(proto::RunEndedEvent::default()),
  );
  assert!(!ingestor.ingest(&ended).unwrap().duplicate);
  assert!(ingestor.ingest(&ended).unwrap().duplicate);
  assert_eq!(
    repo.get_run("idle-run").unwrap().unwrap().status,
    RunStatus::Passed
  );
  repo.update_retention(1).unwrap();
  assert!(repo.get_run("idle-run").unwrap().is_none());
  assert!(repo.get_run("new").unwrap().is_some());
}

#[test]
fn sqlite_timeout_uses_configured_deadline_inclusive_of_boundary() {
  let repo = Repository::connect_sqlite(":memory:", 0).unwrap();
  repo
    .save_run_start("idle-run", "app", "2000-01-01T00:00:00Z", &[])
    .unwrap();
  repo
    .execute_database_query(
      "UPDATE runs SET last_activity_at = strftime('%Y-%m-%dT%H:%M:%fZ', 'now', '-3600 seconds')",
      1,
    )
    .unwrap();
  repo.update_idle_timeout(7200).unwrap();
  assert_eq!(repo.expire_idle_runs().unwrap(), 0);
  repo.update_idle_timeout(3600).unwrap();
  assert_eq!(repo.expire_idle_runs().unwrap(), 1);
}

fn assert_abandoned_evidence(repo: &Repository) {
  let abandoned = repo.get_run("idle-run").unwrap().unwrap();
  assert_eq!(abandoned.status, RunStatus::Abandoned);
  assert!(abandoned.abandoned_at.is_some());
  assert!(abandoned.ended_at.is_none());
  assert!(abandoned.duration_ms.is_none());
  assert_eq!(
    repo
      .get_test("idle-run", "unfinished")
      .unwrap()
      .unwrap()
      .status,
    TestStatus::Abandoned
  );
  assert_eq!(
    repo
      .get_test("idle-run", "finished")
      .unwrap()
      .unwrap()
      .status,
    TestStatus::Passed
  );
  assert_eq!(repo.storage_stats().unwrap().running_runs, 0);
  assert_eq!(
    repo
      .preview_purge(None, None, false, &std::collections::BTreeMap::default())
      .unwrap()
      .run_ids,
    vec!["idle-run"]
  );
  let log = repo.live_events_after(0, 100).unwrap();
  let notification: serde_json::Value = serde_json::from_str(&log.last().unwrap().json).unwrap();
  assert_eq!(notification["event_type"], "run_lifecycle_changed");
  assert_eq!(notification["payload"]["run"]["status"], "ABANDONED");
}
