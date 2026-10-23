use super::{LIFECYCLE_TEST_LOCK, TestSchema, connect_driver, seed_evidence};

#[test]
fn postgres_cleanup_with_large_receipt_backlog() {
  let _guard = LIFECYCLE_TEST_LOCK.lock().unwrap();
  let Some(database) = TestSchema::from_env() else {
    return;
  };
  let repo = database.repository();
  seed_evidence(&repo);
  repo
    .save_run_start("active-run", "app", "2026-10-23T00:00:00Z", &[])
    .unwrap();
  let mut client = connect_driver(&database.url).unwrap();
  client
    .batch_execute(
      "UPDATE runs SET last_activity_at = '2000-01-01T00:00:00.000Z' WHERE id = 'run-1';
       INSERT INTO live_events(event_id, run_id, event_type, payload)
       SELECT 'event-' || n,
              CASE WHEN n <= 2000 THEN 'run-1' ELSE 'active-run' END,
              'entry_recorded', '{}'::jsonb
       FROM generate_series(1, 202000) n;
       INSERT INTO dashboard_event_inbox(event_id, run_id, sequence, live_event_id)
       SELECT event_id, run_id, id, id FROM live_events;
       ANALYZE dashboard_event_inbox;",
    )
    .unwrap();

  // PostgreSQL issues this lookup for every deleted live event. Check the plan
  // as well as the sweep so fast machines cannot hide a missing cascade index.
  let plan = client
    .query(
      "EXPLAIN SELECT event_id FROM dashboard_event_inbox WHERE live_event_id = 1",
      &[],
    )
    .unwrap()
    .iter()
    .map(|row| row.get::<_, String>(0))
    .collect::<Vec<_>>()
    .join("\n");
  assert!(
    plan.contains("idx_dashboard_event_inbox_live_event_id"),
    "Receipt cascade must use its index: {plan}"
  );

  // Exercise the real worker connection with its unchanged 10-second statement
  // timeout. The unindexed cascade times out with this receipt backlog.
  assert_eq!(repo.expire_idle_runs().unwrap(), 1);
  assert!(repo.get_run("run-1").unwrap().is_none());
  assert!(repo.get_run("active-run").unwrap().is_some());
  let evidence = repo.storage_stats().unwrap().evidence;
  assert_eq!(evidence.tests, 0);
  assert_eq!(evidence.entries, 0);
  assert_eq!(evidence.spans, 0);
  assert_eq!(evidence.snapshots, 0);
  let counts = client
    .query_one(
      "SELECT
         (SELECT count(*) FROM dashboard_event_inbox WHERE run_id = 'run-1'),
         (SELECT count(*) FROM live_events WHERE run_id = 'run-1'),
         (SELECT count(*) FROM dashboard_event_inbox WHERE run_id = 'active-run'),
         (SELECT count(*) FROM live_events WHERE run_id = 'active-run'),
         (SELECT count(*) FROM live_events WHERE run_id IS NULL AND event_type = 'run_deleted')",
      &[],
    )
    .unwrap();
  for (column, expected) in [0_i64, 0, 200_000, 200_000, 1].into_iter().enumerate() {
    assert_eq!(counts.get::<_, i64>(column), expected);
  }
}
