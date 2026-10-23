mod cleanup;
mod recovery;

use std::collections::BTreeMap;

use postgres::Client;

use super::database::connect_driver;
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
    let mut root = connect_driver(&database_url).expect("connect to test PostgreSQL");
    root
      .batch_execute(&format!("CREATE SCHEMA {name}"))
      .expect("create isolated test schema");
    let separator = if database_url.contains('?') { '&' } else { '?' };
    let url =
      format!("{database_url}{separator}options=-csearch_path%3D{name}&application_name={name}");
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
    correlation_key: String::new(),
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
  let purge_filter = BTreeMap::from([
    ("gitlab.pipeline_id".into(), vec!["42".into(), "43".into()]),
    ("team".into(), vec!["checkout".into()]),
  ]);
  assert_eq!(
    repo
      .preview_purge(Some("checkout-api"), None, true, &purge_filter)
      .unwrap()
      .run_ids,
    vec!["run-1"]
  );
  assert_eq!(
    repo
      .preview_purge(Some("another-app"), None, true, &purge_filter)
      .unwrap()
      .run_ids
      .len(),
    0
  );
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
    .preview_purge(Some("checkout-api"), None, false, &BTreeMap::new())
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
  let mut client = connect_driver(&database.url).unwrap();
  let version: i64 = client
    .query_one(
      "SELECT MAX(version)::BIGINT FROM refinery_schema_history",
      &[],
    )
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

#[test]
fn postgres_top_filter_is_shared_and_uses_the_latest_matching_run() {
  use crate::storage::models::TopFilterSettings;

  let Some(database) = TestSchema::from_env() else {
    return;
  };
  let repo = database.repository();
  let second_replica = database.repository();
  for (id, team, day) in [
    ("old", "checkout", "01"),
    ("match", "checkout", "02"),
    ("new", "payments", "03"),
  ] {
    repo
      .save_run_start_with_metadata(
        id,
        "shared-api",
        &format!("2024-01-{day}T00:00:00Z"),
        None,
        &[],
        &BTreeMap::from([("gitlab.team".into(), team.into())]),
      )
      .unwrap();
  }
  repo
    .update_top_filter(&TopFilterSettings {
      metadata_key: Some("gitlab.team".into()),
    })
    .unwrap();
  let catalog = second_replica.top_filter_catalog().unwrap();
  assert_eq!(catalog.available_keys, vec!["gitlab.team"]);
  assert_eq!(catalog.values.len(), 2);
  assert_eq!(catalog.values[0].value, "checkout");
  assert_eq!(catalog.values[0].apps[0].latest_run_id, "match");
  assert_eq!(catalog.values[1].apps[0].latest_run_id, "new");
  drop(repo);
  assert_eq!(
    database
      .repository()
      .top_filter_catalog()
      .unwrap()
      .settings
      .metadata_key
      .as_deref(),
    Some("gitlab.team")
  );
  second_replica
    .update_top_filter(&TopFilterSettings { metadata_key: None })
    .unwrap();
  assert!(
    database
      .repository()
      .top_filter_catalog()
      .unwrap()
      .values
      .is_empty()
  );
}

static LIFECYCLE_TEST_LOCK: std::sync::Mutex<()> = std::sync::Mutex::new(());

#[test]
fn postgres_idle_lifecycle_and_shared_settings() {
  let _guard = LIFECYCLE_TEST_LOCK.lock().unwrap();
  let Some(database) = TestSchema::from_env() else {
    return;
  };
  let repo = std::sync::Arc::new(database.repository());
  crate::storage::repository::lifecycle::tests::lifecycle_round_trip(&repo);
  let other = database.repository();
  other.update_idle_timeout(1234).unwrap();
  repo.initialize_idle_timeout(3600).unwrap();
  assert_eq!(repo.storage_stats().unwrap().run_idle_timeout_seconds, 1234);
}

#[test]
fn postgres_sweep_lock_is_exclusive_and_released_on_disconnect() {
  let _guard = LIFECYCLE_TEST_LOCK.lock().unwrap();
  let Some(database) = TestSchema::from_env() else {
    return;
  };
  let repo = database.repository();
  repo
    .save_run_start("idle-run", "app", "2000-01-01T00:00:00Z", &[])
    .unwrap();
  repo
    .execute_database_query(
      "UPDATE runs SET last_activity_at = '2000-01-01T00:00:00.000Z'",
      1,
    )
    .unwrap();
  let mut owner = connect_driver(&database.url).unwrap();
  assert!(
    owner
      .query_one("SELECT pg_try_advisory_lock(1937012086, 2)", &[])
      .unwrap()
      .get::<_, bool>(0)
  );
  assert_eq!(repo.expire_idle_runs().unwrap(), 0);
  drop(owner);
  assert_eq!(repo.expire_idle_runs().unwrap(), 1);
  let mut next_owner = connect_driver(&database.url).unwrap();
  assert!(
    next_owner
      .query_one("SELECT pg_try_advisory_lock(1937012086, 2)", &[])
      .unwrap()
      .get::<_, bool>(0)
  );
}

#[test]
fn postgres_expiry_rechecks_activity_after_waiting_for_ingestion() {
  let _guard = LIFECYCLE_TEST_LOCK.lock().unwrap();
  let Some(mut database) = TestSchema::from_env() else {
    return;
  };
  let repo = std::sync::Arc::new(database.repository());
  repo
    .save_run_start("idle-run", "app", "2000-01-01T00:00:00Z", &[])
    .unwrap();
  repo
    .execute_database_query(
      "UPDATE runs SET last_activity_at = '2000-01-01T00:00:00.000Z'",
      1,
    )
    .unwrap();
  let mut writer = connect_driver(&database.url).unwrap();
  let mut transaction = writer.transaction().unwrap();
  transaction
    .query_one(
      "SELECT pg_advisory_xact_lock(hashtextextended('idle-run', 0))",
      &[],
    )
    .unwrap();
  let worker_repo = repo.clone();
  let worker = std::thread::spawn(move || worker_repo.expire_idle_runs());
  let deadline = std::time::Instant::now() + std::time::Duration::from_secs(1);
  loop {
    let waiting: bool = database.root.query_one(
      "SELECT EXISTS (SELECT 1 FROM pg_stat_activity WHERE application_name = $1 AND wait_event = 'advisory')",
      &[&database.name],
    ).unwrap().get(0);
    if waiting {
      break;
    }
    assert!(
      std::time::Instant::now() < deadline,
      "sweeper did not wait for the ingestion lock"
    );
    std::thread::sleep(std::time::Duration::from_millis(10));
  }
  transaction.execute("UPDATE runs SET last_activity_at = to_char(clock_timestamp() AT TIME ZONE 'UTC', 'YYYY-MM-DD\"T\"HH24:MI:SS.MS\"Z\"') WHERE id = 'idle-run'", &[]).unwrap();
  transaction.commit().unwrap();
  assert_eq!(worker.join().unwrap().unwrap(), 0);
  assert_eq!(
    repo.get_run("idle-run").unwrap().unwrap().status,
    crate::storage::models::RunStatus::Running
  );
}
