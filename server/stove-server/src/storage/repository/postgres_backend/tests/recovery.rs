use std::sync::Arc;
use std::time::{Duration, Instant};

use axum::Json;
use axum::extract::State;
use axum::http::StatusCode;
use axum::response::IntoResponse;
use diesel::prelude::*;
use diesel::sql_types::Integer;

use super::{TestSchema, assert_round_trip, seed_evidence};
use crate::http::routes::{DatabaseQueryRequest, execute_database_query};
use crate::http::server::AppState;
use crate::ingest::EventIngestor;
use crate::sse::manager::SseManager;
use crate::storage::repository::Backend;
use crate::storage::repository::postgres_backend::PostgresBackend;

fn pid(connection: &mut PgConnection) -> i32 {
  diesel::select(diesel::dsl::sql::<Integer>("pg_backend_pid()"))
    .get_result(connection)
    .unwrap()
}

fn terminate(database: &mut TestSchema, pid: i32) {
  assert!(
    database
      .root
      .query_one("SELECT pg_terminate_backend($1, 5000)", &[&pid])
      .unwrap()
      .get::<_, bool>(0)
  );
}

#[test]
fn reconnects_read_write_and_explorer_without_losing_data_or_settings() {
  let Some(mut database) = TestSchema::from_env() else {
    return;
  };
  let repo = database.repository();
  let metadata = seed_evidence(&repo);
  repo.update_retention(7).unwrap();
  let Backend::Postgres(backend) = &repo.backend else {
    unreachable!()
  };

  for _ in 0..2 {
    terminate(&mut database, pid(&mut backend.read.get().unwrap()));
    terminate(&mut database, pid(&mut backend.write.get().unwrap()));
    let explorer_pid = backend
      .explorer
      .get()
      .unwrap()
      .query_one("SELECT pg_backend_pid()", &[])
      .unwrap()
      .get(0);
    terminate(&mut database, explorer_pid);

    // Each lane must recover on its first operation, without a server restart.
    assert_round_trip(&repo, &metadata);
    repo
      .save_test_end("run-1", "test-1", "PASSED", 10, "", "2024-01-01T00:00:03Z")
      .unwrap();
    assert!(
      repo
        .database_schema()
        .unwrap()
        .tables
        .iter()
        .any(|table| table.name == "runs")
    );
    assert_eq!(
      repo.execute_database_query("SELECT 42", 1).unwrap().rows[0][0].as_deref(),
      Some("42")
    );
    assert_eq!(repo.retention_runs_per_app(), 7);
    repo.check_health().unwrap();
  }
}

#[test]
fn health_reports_explorer_pool_exhaustion_and_recovers_after_return() {
  let Some(database) = TestSchema::from_env() else {
    return;
  };
  let backend = PostgresBackend::connect(&database.url, 2).unwrap();
  let held: Vec<_> = (0..backend.explorer.max_size())
    .map(|_| backend.explorer.get().unwrap())
    .collect();
  let started = Instant::now();
  let error = backend.check_health().unwrap_err();
  assert!(error.is_database_unavailable());
  assert_eq!(
    error.into_response().status(),
    StatusCode::SERVICE_UNAVAILABLE
  );
  assert!(
    started.elapsed() < Duration::from_secs(5),
    "pool exhaustion must not block indefinitely"
  );
  drop(held);
  backend.check_health().unwrap();
}

#[test]
fn concurrent_reads_have_separate_capacity_from_ingestion() {
  let Some(database) = TestSchema::from_env() else {
    return;
  };
  let repo = database.repository();
  let Backend::Postgres(backend) = &repo.backend else {
    unreachable!()
  };
  let held: Vec<_> = (0..backend.read.max_size())
    .map(|_| backend.read.get().unwrap())
    .collect();
  // All read slots are occupied, but writes and explorer queries still work.
  repo
    .save_run_start("pooled-run", "app", "2024-01-01T00:00:00Z", &[])
    .unwrap();
  assert_eq!(
    repo
      .execute_database_query("SELECT count(*) FROM runs", 1)
      .unwrap()
      .rows[0][0]
      .as_deref(),
    Some("1")
  );
  let error = repo.get_runs(None).unwrap_err();
  assert!(error.is_database_unavailable());
  drop(held);
  assert_eq!(repo.get_runs(None).unwrap().len(), 1);
}

#[test]
fn explorer_disconnect_is_503_without_replaying_sql_and_next_query_recovers() {
  let Some(mut database) = TestSchema::from_env() else {
    return;
  };
  let repo = Arc::new(database.repository());
  // Sequence increments survive rollback, exposing any automatic replay.
  database
    .root
    .batch_execute(&format!(
      "CREATE SEQUENCE {0}.attempts;
     CREATE FUNCTION {0}.disconnect_after_effect() RETURNS boolean LANGUAGE plpgsql AS $$
     BEGIN
       PERFORM nextval('{0}.attempts');
       PERFORM pg_terminate_backend(pg_backend_pid());
       RETURN true;
     END $$",
      database.name
    ))
    .unwrap();
  let sse_manager = Arc::new(SseManager::new());
  let state = AppState {
    public_url: None,
    repository: repo.clone(),
    ingestor: EventIngestor::new(repo.clone(), sse_manager.clone()),
    sse_manager,
    health_probe: Arc::new(tokio::sync::Semaphore::new(1)),
  };
  let runtime = tokio::runtime::Runtime::new().unwrap();
  for (sql, expected) in [
    (
      "SELECT disconnect_after_effect()",
      StatusCode::SERVICE_UNAVAILABLE,
    ),
    ("SELECT 42", StatusCode::OK),
    ("SELEC invalid syntax", StatusCode::BAD_REQUEST),
    ("SELECT 43", StatusCode::OK),
  ] {
    let response = runtime
      .block_on(execute_database_query(
        State(state.clone()),
        Json(DatabaseQueryRequest {
          sql: sql.into(),
          max_rows: 1,
        }),
      ))
      .into_response();
    assert_eq!(response.status(), expected, "{sql}");
  }
  let attempts: i64 = database
    .root
    .query_one(
      &format!("SELECT last_value FROM {}.attempts", database.name),
      &[],
    )
    .unwrap()
    .get(0);
  assert_eq!(attempts, 1, "interrupted SQL must not be replayed");
}

#[test]
fn live_event_listener_reconnects_after_termination() {
  let Some(mut database) = TestSchema::from_env() else {
    return;
  };
  let backend = PostgresBackend::connect(&database.url, 2).unwrap();
  let mut receiver = backend.subscribe_live_event_notifications();
  let first_pid = wait_for_listener(&mut database, None);
  terminate(&mut database, first_pid);
  wait_for_listener(&mut database, Some(first_pid));
  let metrics = backend.metrics.prometheus();
  assert!(
    metrics
      .lines()
      .any(|line| line.starts_with("stove_app_worker_runs_total{")
        && line.contains("worker=\"postgres_notifications\"")
        && line.contains("outcome=\"error\"")
        && !line.ends_with(" 0"))
  );
  assert!(metrics.lines().any(|line| {
    line.starts_with("stove_app_worker_last_success_timestamp_seconds{")
      && line.contains("worker=\"postgres_notifications\"")
      && !line.ends_with(" 0")
  }));

  database
    .root
    .batch_execute("NOTIFY stove_live_events")
    .unwrap();
  let deadline = Instant::now() + Duration::from_secs(5);
  loop {
    if receiver.try_recv().is_ok() {
      break;
    }
    assert!(
      Instant::now() < deadline,
      "reconnected listener did not deliver notification"
    );
    std::thread::sleep(Duration::from_millis(20));
  }
  drop(receiver);
  // Wait until the listener observes closure and releases its connection.
  let deadline = Instant::now() + Duration::from_secs(5);
  while listener_pid(&mut database, None).is_some() {
    assert!(
      Instant::now() < deadline,
      "listener did not stop when subscriber closed"
    );
    std::thread::sleep(Duration::from_millis(20));
  }
}

fn listener_pid(database: &mut TestSchema, previous: Option<i32>) -> Option<i32> {
  database
    .root
    .query_opt(
      "SELECT pid FROM pg_stat_activity WHERE application_name = $1
     AND query = 'LISTEN stove_live_events' AND state = 'idle'
     AND ($2::int IS NULL OR pid <> $2)",
      &[&database.name, &previous],
    )
    .unwrap()
    .map(|row| row.get(0))
}

fn wait_for_listener(database: &mut TestSchema, previous: Option<i32>) -> i32 {
  let deadline = Instant::now() + Duration::from_secs(5);
  loop {
    if let Some(pid) = listener_pid(database, previous) {
      return pid;
    }
    assert!(Instant::now() < deadline, "listener did not connect");
    std::thread::sleep(Duration::from_millis(20));
  }
}

#[test]
fn metrics_report_pool_pressure_timeouts_and_privileged_diagnostics() {
  let Some(database) = TestSchema::from_env() else {
    return;
  };
  let repo = database.repository();
  let Backend::Postgres(backend) = &repo.backend else {
    unreachable!()
  };
  let diagnostics = backend.diagnostics().unwrap();
  assert!(diagnostics.database_bytes.unwrap() > 0);
  assert!(diagnostics.blocked_sessions.is_some());
  assert!(diagnostics.dead_tuples.is_some());
  assert!(backend.probe_roles().iter().all(|check| check.healthy));
  let held: Vec<_> = (0..backend.read.max_size())
    .map(|_| backend.read.get().unwrap())
    .collect();
  backend.metrics.sample(backend.capacity());
  assert!(repo.get_runs(None).is_err());
  let text = repo.metrics.prometheus();
  assert!(
    text
      .lines()
      .any(|line| line.starts_with("stove_db_capacity_busy{")
        && line.contains("role=\"read\"")
        && line.ends_with(" 4"))
  );
  assert!(
    text
      .lines()
      .any(|line| line.starts_with("stove_db_timeouts_total{")
        && line.contains("kind=\"wait\"")
        && line.ends_with(" 1"))
  );
  // Dedicated monitoring session works even with every read connection held.
  assert!(backend.diagnostics().is_ok());
  drop(held);
  assert!(repo.get_runs(None).is_ok());
}

#[test]
fn restricted_postgres_role_omits_session_statistics_instead_of_claiming_zero() {
  let Some(mut database) = TestSchema::from_env() else {
    return;
  };
  let mut backend = PostgresBackend::connect(&database.url, 2).unwrap();
  let role = format!("metrics_{}", uuid::Uuid::new_v4().simple());
  database
    .root
    .batch_execute(&format!(
      "CREATE ROLE {role} LOGIN PASSWORD 'metrics-test'; GRANT USAGE ON SCHEMA {} TO {role}",
      database.name
    ))
    .unwrap();
  let mut url = reqwest::Url::parse(&database.url).unwrap();
  url.set_username(&role).unwrap();
  url.set_password(Some("metrics-test")).unwrap();
  backend.database_url = url.to_string();
  let diagnostics = backend.diagnostics().unwrap();
  assert!(diagnostics.database_bytes.is_some());
  assert_eq!(diagnostics.blocked_sessions, None);
  assert_eq!(diagnostics.oldest_transaction_seconds, None);
  assert!(
    diagnostics
      .notes
      .iter()
      .any(|note| note.contains("pg_read_all_stats"))
  );
  drop(backend);
  database
    .root
    .batch_execute(&format!(
      "REVOKE USAGE ON SCHEMA {} FROM {role}; DROP ROLE {role}",
      database.name
    ))
    .unwrap();
}
