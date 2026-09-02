use super::super::admin::{PurgeCandidate, select_purge_candidates};
use super::SqliteBackend;
use super::writes::{delete_runs_on, prune_completed_runs_for_app_on};
use crate::error::Result;
use crate::storage::models::{EvidenceCounts, PurgePreview, PurgeResult, StorageStats};

impl SqliteBackend {
  pub fn storage_stats(&self, retention_runs_per_app: usize) -> Result<StorageStats> {
    let db = self.lock_read();
    let conn = db.conn();
    Ok(conn.query_row(
      "SELECT
        (SELECT COUNT(*) FROM runs),
        (SELECT COUNT(*) FROM runs WHERE status = 'RUNNING'),
        (SELECT COUNT(*) FROM tests),
        (SELECT COUNT(*) FROM entries),
        (SELECT COUNT(*) FROM spans),
        (SELECT COUNT(*) FROM snapshots),
        (SELECT COUNT(*) FROM mock_interactions),
        (SELECT COUNT(*) FROM mock_warnings)",
      [],
      |row| {
        Ok(StorageStats {
          backend: "sqlite".to_string(),
          retention_runs_per_app,
          runs: row.get(0)?,
          running_runs: row.get(1)?,
          evidence: EvidenceCounts {
            tests: row.get(2)?,
            entries: row.get(3)?,
            spans: row.get(4)?,
            snapshots: row.get(5)?,
            mock_interactions: row.get(6)?,
            mock_warnings: row.get(7)?,
          },
        })
      },
    )?)
  }

  pub fn update_retention(&self, runs_per_app: usize) -> Result<()> {
    if runs_per_app > 0 {
      let mut db = self.lock_write();
      let tx = db.conn_mut().unchecked_transaction()?;
      let apps = {
        let mut stmt = tx.prepare("SELECT DISTINCT app_name FROM runs")?;
        stmt
          .query_map([], |row| row.get::<_, String>(0))?
          .collect::<rusqlite::Result<Vec<_>>>()?
      };
      for app_name in apps {
        prune_completed_runs_for_app_on(&tx, &app_name, runs_per_app)?;
      }
      tx.commit()?;
    }
    Ok(())
  }

  pub fn preview_purge(
    &self,
    app_name: Option<&str>,
    older_than: Option<&str>,
    include_running: bool,
  ) -> Result<PurgePreview> {
    let db = self.lock_read();
    let conn = db.conn();
    let mut stmt = conn
      .prepare("SELECT id, app_name, started_at, status FROM runs ORDER BY started_at, rowid")?;
    let candidates = stmt
      .query_map([], |row| {
        Ok(PurgeCandidate {
          run_id: row.get(0)?,
          app_name: row.get(1)?,
          started_at: row.get(2)?,
          status: row.get(3)?,
        })
      })?
      .collect::<rusqlite::Result<Vec<_>>>()?;
    let run_ids = select_purge_candidates(candidates, app_name, older_than, include_running);
    let evidence = evidence_counts(conn, &run_ids)?;
    Ok(PurgePreview {
      run_count: run_ids.len(),
      run_ids,
      evidence,
    })
  }

  pub fn purge_runs(&self, run_ids: &[String], include_running: bool) -> Result<PurgeResult> {
    let mut db = self.lock_write();
    let tx = db.conn_mut().unchecked_transaction()?;
    let selected = select_requested_runs(&tx, run_ids, include_running)?;
    let evidence = evidence_counts(&tx, &selected)?;
    delete_runs_on(&tx, &selected)?;
    tx.commit()?;
    Ok(PurgeResult {
      purged_runs: selected.len(),
      purged_run_ids: selected,
      evidence,
    })
  }
}

fn evidence_counts(conn: &rusqlite::Connection, run_ids: &[String]) -> Result<EvidenceCounts> {
  Ok(EvidenceCounts {
    tests: count_for_runs(conn, "tests", run_ids)?,
    entries: count_for_runs(conn, "entries", run_ids)?,
    spans: count_for_runs(conn, "spans", run_ids)?,
    snapshots: count_for_runs(conn, "snapshots", run_ids)?,
    mock_interactions: count_for_runs(conn, "mock_interactions", run_ids)?,
    mock_warnings: count_for_runs(conn, "mock_warnings", run_ids)?,
  })
}

fn select_requested_runs(
  conn: &rusqlite::Connection,
  run_ids: &[String],
  include_running: bool,
) -> Result<Vec<String>> {
  if run_ids.is_empty() {
    return Ok(Vec::new());
  }
  let run_ids = serde_json::to_string(run_ids)?;
  let mut statement = conn.prepare(
    "SELECT requested.value
       FROM json_each(?1) requested
       JOIN runs ON runs.id = requested.value
      WHERE ?2 OR runs.status <> 'RUNNING'
      ORDER BY requested.key",
  )?;
  Ok(
    statement
      .query_map(rusqlite::params![run_ids, include_running], |row| {
        row.get(0)
      })?
      .collect::<rusqlite::Result<Vec<_>>>()?,
  )
}

fn count_for_runs(conn: &rusqlite::Connection, table: &str, run_ids: &[String]) -> Result<i64> {
  if run_ids.is_empty() {
    return Ok(0);
  }
  let run_ids = serde_json::to_string(run_ids)?;
  Ok(conn.query_row(
    &format!("SELECT COUNT(*) FROM {table} WHERE run_id IN (SELECT value FROM json_each(?1))"),
    rusqlite::params![run_ids],
    |row| row.get(0),
  )?)
}
