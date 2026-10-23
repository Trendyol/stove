use refinery::embed_migrations;
use rusqlite::Connection;

use crate::error::Result;

mod embedded {
  use super::embed_migrations;

  embed_migrations!("src/storage/migrations/sqlite");
}

/// Apply pending `SQLite` migrations with Refinery.
pub(crate) fn run_migrations(connection: &mut Connection) -> Result<()> {
  embedded::migrations::runner().run(connection)?;
  Ok(())
}

#[cfg(test)]
pub(crate) fn migration_count() -> usize {
  embedded::migrations::runner().get_migrations().len()
}

#[test]
fn lifecycle_backfills_receipts_and_gives_legacy_runs_a_fresh_grace_period() {
  let mut connection = Connection::open_in_memory().unwrap();
  embedded::migrations::runner()
    .set_target(refinery::Target::Version(10))
    .run(&mut connection)
    .unwrap();
  connection.execute_batch("
    INSERT INTO runs(id, app_name, started_at) VALUES ('legacy', 'app', '2000-01-01'), ('received', 'app', '2000-01-01');
    INSERT INTO live_events(id, event_id, run_id, event_type, payload) VALUES (1, 'event', 'received', 'run_started', '{}');
    INSERT INTO dashboard_event_inbox(event_id, run_id, live_event_id, created_at) VALUES ('event', 'received', 1, '2026-01-02 03:04:05');
  ").unwrap();
  let before = chrono::Utc::now();
  run_migrations(&mut connection).unwrap();
  let receipt: String = connection
    .query_row(
      "SELECT last_activity_at FROM runs WHERE id = 'received'",
      [],
      |row| row.get(0),
    )
    .unwrap();
  assert_eq!(receipt, "2026-01-02T03:04:05.000Z");
  let legacy: String = connection
    .query_row(
      "SELECT last_activity_at FROM runs WHERE id = 'legacy'",
      [],
      |row| row.get(0),
    )
    .unwrap();
  let legacy = chrono::DateTime::parse_from_rfc3339(&legacy).unwrap();
  assert!(legacy.timestamp_millis() >= before.timestamp_millis());
  assert!(legacy <= chrono::Utc::now());
  run_migrations(&mut connection).unwrap();
}

#[test]
fn idle_cleanup_migration_preserves_receipts_and_event_sequence() {
  for keep_active in [false, true] {
    let mut connection = Connection::open_in_memory().unwrap();
    connection.execute_batch("PRAGMA foreign_keys=ON").unwrap();
    embedded::migrations::runner()
      .set_target(refinery::Target::Version(10))
      .run(&mut connection)
      .unwrap();
    connection
      .execute_batch(
        "
      INSERT INTO runs(id, app_name, started_at, status) VALUES
        ('active', 'app', '2026-01-01', 'RUNNING');
      INSERT INTO live_events(id, event_id, run_id, event_type, payload) VALUES
        (20, 'active-event', 'active', 'run_started', '{}');
      INSERT INTO dashboard_event_inbox(event_id, run_id, sequence, live_event_id) VALUES
        ('active-event', 'active', 1, 20);
    ",
      )
      .unwrap();
    if !keep_active {
      connection
        .execute_batch("DELETE FROM runs WHERE id='active'")
        .unwrap();
    }
    run_migrations(&mut connection).unwrap();
    let count = |table: &str| {
      connection
        .query_row(&format!("SELECT count(*) FROM {table}"), [], |r| {
          r.get::<_, i64>(0)
        })
        .unwrap()
    };
    assert_eq!(count("runs"), i64::from(keep_active));
    assert_eq!(count("tests"), 0);
    assert_eq!(count("dashboard_event_inbox"), i64::from(keep_active));
    assert_eq!(count("live_events"), i64::from(keep_active));
    connection.execute_batch("INSERT INTO live_events(event_id, run_id, event_type, payload) VALUES ('deleted', NULL, 'run_deleted', '{}')").unwrap();
    assert!(connection.last_insert_rowid() > 20);
    let violations = connection
      .prepare("PRAGMA foreign_key_check")
      .unwrap()
      .query_map([], |_| Ok(()))
      .unwrap()
      .count();
    assert_eq!(violations, 0);
    run_migrations(&mut connection).unwrap();
  }
}
