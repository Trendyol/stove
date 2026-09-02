use std::collections::BTreeMap;

use postgres::Client;

use super::connect;
use crate::storage::migrations::postgres::migration_count;
use crate::storage::models::{NewEntry, NewSpan};
use crate::storage::repository::Repository;

struct TestSchema {
  root: Client,
  name: String,
  url: String,
}

impl TestSchema {
  fn from_env() -> Option<Self> {
    let database_url = std::env::var("STOVE_TEST_POSTGRES_URL").ok()?;
    let name = format!("stove_test_{}", uuid::Uuid::new_v4().simple());
    let mut root = connect(&database_url).expect("connect to test PostgreSQL");
    root
      .batch_execute(&format!("CREATE SCHEMA {name}"))
      .expect("create isolated test schema");
    let separator = if database_url.contains('?') { '&' } else { '?' };
    let url = format!("{database_url}{separator}options=-csearch_path%3D{name}");
    Some(Self { root, name, url })
  }

  fn repository(&self) -> Repository {
    Repository::connect_postgres(&self.url, 2).expect("initialize PostgreSQL backend")
  }
}

impl Drop for TestSchema {
  fn drop(&mut self) {
    self
      .root
      .batch_execute(&format!("DROP SCHEMA {} CASCADE", self.name))
      .expect("drop isolated test schema");
  }
}

#[test]
fn postgres_round_trip_retention_admin_and_migrations() {
  let Some(database) = TestSchema::from_env() else {
    return;
  };
  let repo = database.repository();
  let metadata = seed_evidence(&repo);

  assert_eq!(repo.backend_kind(), "postgresql");
  assert_round_trip(&repo, &metadata);
  finish_run_and_apply_retention(&repo);
  assert_admin_operations(&repo);
  drop(repo);

  assert_migrations_are_idempotent_and_indexed(&database);
}

fn seed_evidence(repo: &Repository) -> BTreeMap<String, String> {
  let metadata = BTreeMap::from([
    ("team".to_string(), "checkout".to_string()),
    ("gitlab.pipeline_id".to_string(), "42".to_string()),
  ]);
  repo
    .save_run_start_with_metadata(
      "run-1",
      "checkout-api",
      "2024-01-01T00:00:00Z",
      Some("0.23.2"),
      &["HTTP".to_string()],
      &metadata,
    )
    .unwrap();
  repo
    .save_test_start(
      "run-1",
      "test-1",
      "creates order",
      "CheckoutSpec",
      &["checkout".to_string()],
      "2024-01-01T00:00:01Z",
    )
    .unwrap();
  repo.save_entry(&entry()).unwrap();
  repo.save_span(&span()).unwrap();
  repo
    .save_snapshot(
      "run-1",
      "test-1",
      "Kafka",
      r#"{"published":1}"#,
      "1 message",
    )
    .unwrap();
  metadata
}

fn entry() -> NewEntry {
  NewEntry {
    run_id: "run-1".into(),
    test_id: "test-1".into(),
    timestamp: "2024-01-01T00:00:02Z".into(),
    system: "HTTP".into(),
    action: "POST /orders".into(),
    result: "PASSED".into(),
    input: "{}".into(),
    output: r#"{"id":"order-1"}"#.into(),
    metadata: "{}".into(),
    expected: String::new(),
    actual: String::new(),
    error: String::new(),
    trace_id: "trace-1".into(),
    assertion_id: "assertion-1".into(),
  }
}

fn span() -> NewSpan {
  NewSpan {
    run_id: "run-1".into(),
    trace_id: "trace-1".into(),
    span_id: "span-1".into(),
    operation_name: "POST /orders".into(),
    service_name: "checkout-api".into(),
    start_time_nanos: 1,
    end_time_nanos: 2,
    status: "OK".into(),
    ..Default::default()
  }
}

fn assert_round_trip(repo: &Repository, metadata: &BTreeMap<String, String>) {
  assert_eq!(repo.get_run("run-1").unwrap().unwrap().metadata, *metadata);
  assert_eq!(repo.get_tests_for_run("run-1").unwrap().len(), 1);
  assert_eq!(repo.get_entries("run-1", "test-1").unwrap().len(), 1);
  assert_eq!(repo.get_spans_for_test("run-1", "test-1").unwrap().len(), 1);
  assert_eq!(repo.get_snapshots("run-1", "test-1").unwrap().len(), 1);
  let filter = BTreeMap::from([("gitlab.pipeline_id".to_string(), "42".to_string())]);
  assert_eq!(repo.get_runs_filtered(None, &filter).unwrap().len(), 1);
}

fn finish_run_and_apply_retention(repo: &Repository) {
  repo
    .save_test_end("run-1", "test-1", "PASSED", 10, "", "2024-01-01T00:00:03Z")
    .unwrap();
  repo
    .save_run_end("run-1", "2024-01-01T00:00:04Z", 1, 1, 0, 10)
    .unwrap();
  for (id, started_at) in [
    ("run-2", "2024-02-01T00:00:00Z"),
    ("run-3", "2024-03-01T00:00:00Z"),
  ] {
    repo
      .save_run_start(id, "checkout-api", started_at, &[])
      .unwrap();
    repo.save_run_end(id, started_at, 0, 0, 0, 1).unwrap();
  }
  assert!(repo.get_run("run-1").unwrap().is_none());
  assert_eq!(repo.storage_stats().unwrap().runs, 2);
}

fn assert_admin_operations(repo: &Repository) {
  repo
    .save_run_start("run-active", "checkout-api", "2024-04-01T00:00:00Z", &[])
    .unwrap();
  let completed = repo
    .preview_purge(Some("checkout-api"), None, false)
    .unwrap();
  assert_eq!(completed.run_ids, vec!["run-2", "run-3"]);
  let purged = repo.purge_runs(&["run-2".to_string()], false).unwrap();
  assert_eq!(purged.purged_run_ids, vec!["run-2"]);
  assert_eq!(
    repo
      .purge_runs(&["run-active".to_string()], false)
      .unwrap()
      .purged_runs,
    0
  );
}

fn assert_migrations_are_idempotent_and_indexed(database: &TestSchema) {
  let repo = database.repository();
  assert_eq!(repo.get_runs(None).unwrap().len(), 2);
  let mut client = connect(&database.url).unwrap();
  let version: i64 = client
    .query_one("SELECT MAX(version) FROM schema_migrations", &[])
    .unwrap()
    .get(0);
  assert_eq!(version, i64::try_from(migration_count()).unwrap());
  let index_definition: String = client
    .query_one(
      "SELECT indexdef FROM pg_indexes WHERE schemaname = current_schema()
        AND indexname = 'idx_runs_metadata'",
      &[],
    )
    .unwrap()
    .get(0);
  assert!(index_definition.contains("USING gin"));
}
