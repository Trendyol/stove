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
    terminate(&mut database, pid(&mut backend.read.lock().unwrap()));
    terminate(&mut database, pid(&mut backend.write.lock().unwrap()));
    let explorer_pid = backend
      .explorer
      .lock()
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
fn health_reports_explorer_outage_and_recovers_when_database_returns() {
  let Some(mut database) = TestSchema::from_env() else {
    return;
  };
  let mut backend = PostgresBackend::connect(&database.url, 2).unwrap();
  let explorer_pid = backend
    .explorer
    .lock()
    .unwrap()
    .query_one("SELECT pg_backend_pid()", &[])
    .unwrap()
    .get(0);
  terminate(&mut database, explorer_pid);
  // Existing read/write connections remain healthy; only explorer must reconnect.
  // Accept and immediately close sockets to simulate an unavailable database.
  let socket = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
  let port = socket.local_addr().unwrap().port();
  backend.database_url = format!(
    "postgresql://stove_test@127.0.0.1:{port}/stove_test?sslmode=disable&connect_timeout=1"
  );
  let reject = std::thread::spawn(move || {
    for _ in 0..2 {
      drop(socket.accept().unwrap());
    }
  });
  for _ in 0..2 {
    let error = backend.check_health().unwrap_err();
    assert!(error.is_database_unavailable());
    assert_eq!(
      error.into_response().status(),
      StatusCode::SERVICE_UNAVAILABLE
    );
  }
  reject.join().unwrap();
  backend.database_url.clone_from(&database.url);
  backend.check_health().unwrap();
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
