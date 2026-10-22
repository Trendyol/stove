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
