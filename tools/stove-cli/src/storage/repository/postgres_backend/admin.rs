use postgres::GenericClient;

use crate::error::Result;
use crate::storage::models::{EvidenceCounts, PurgePreview, PurgeResult, StorageStats};

use super::PostgresBackend;
use super::writes::prune_app;
use crate::storage::repository::admin::{PurgeCandidate, select_purge_candidates};

impl PostgresBackend {
  pub fn storage_stats(&self, retention: usize) -> Result<StorageStats> {
    let mut client = self.lock_read();
    let row = client.query_one(
      "SELECT
      (SELECT COUNT(*) FROM runs),
      (SELECT COUNT(*) FROM runs WHERE status = 'RUNNING'),
      (SELECT COUNT(*) FROM tests),
      (SELECT COUNT(*) FROM entries),
      (SELECT COUNT(*) FROM spans),
      (SELECT COUNT(*) FROM snapshots),
      (SELECT COUNT(*) FROM mock_interactions),
      (SELECT COUNT(*) FROM mock_warnings)",
      &[],
    )?;
    Ok(StorageStats {
      backend: "postgresql".to_string(),
      retention_runs_per_app: retention,
      runs: row.get(0),
      running_runs: row.get(1),
      evidence: EvidenceCounts {
        tests: row.get(2),
        entries: row.get(3),
        spans: row.get(4),
        snapshots: row.get(5),
        mock_interactions: row.get(6),
        mock_warnings: row.get(7),
      },
    })
  }

  pub fn update_retention(&self, retention: usize) -> Result<()> {
    if retention == 0 {
      return Ok(());
    }
    let mut client = self.lock_write();
    let mut tx = client.transaction()?;
    let apps: Vec<String> = tx
      .query("SELECT DISTINCT app_name FROM runs", &[])?
      .iter()
      .map(|row| row.get(0))
      .collect();
    for app_name in apps {
      prune_app(&mut tx, &app_name, retention)?;
    }
    tx.commit()?;
    Ok(())
  }

  pub fn preview_purge(
    &self,
    app_name: Option<&str>,
    older_than: Option<&str>,
    include_running: bool,
  ) -> Result<PurgePreview> {
    let mut client = self.lock_read();
    let candidates = client.query(
      "SELECT id, app_name, started_at, status FROM runs ORDER BY started_at, id",
      &[],
    )?;
    let candidates = candidates
      .into_iter()
      .map(|row| PurgeCandidate {
        run_id: row.get(0),
        app_name: row.get(1),
        started_at: row.get(2),
        status: row.get(3),
      })
      .collect::<Vec<_>>();
    let run_ids = select_purge_candidates(candidates, app_name, older_than, include_running);
    let evidence = evidence_counts(&mut *client, &run_ids)?;
    Ok(PurgePreview {
      run_count: run_ids.len(),
      run_ids,
      evidence,
    })
  }

  pub fn purge_runs(&self, run_ids: &[String], include_running: bool) -> Result<PurgeResult> {
    let mut client = self.lock_write();
    let mut tx = client.transaction()?;
    let selected = select_requested_runs(&mut tx, run_ids, include_running)?;
    let evidence = evidence_counts(&mut tx, &selected)?;
    if !selected.is_empty() {
      tx.execute("DELETE FROM runs WHERE id = ANY($1)", &[&selected])?;
    }
    tx.commit()?;
    Ok(PurgeResult {
      purged_runs: selected.len(),
      purged_run_ids: selected,
      evidence,
    })
  }
}

fn evidence_counts<C: GenericClient>(client: &mut C, run_ids: &[String]) -> Result<EvidenceCounts> {
  Ok(EvidenceCounts {
    tests: count_for_runs(client, "tests", run_ids)?,
    entries: count_for_runs(client, "entries", run_ids)?,
    spans: count_for_runs(client, "spans", run_ids)?,
    snapshots: count_for_runs(client, "snapshots", run_ids)?,
    mock_interactions: count_for_runs(client, "mock_interactions", run_ids)?,
    mock_warnings: count_for_runs(client, "mock_warnings", run_ids)?,
  })
}

fn select_requested_runs<C: GenericClient>(
  client: &mut C,
  run_ids: &[String],
  include_running: bool,
) -> Result<Vec<String>> {
  if run_ids.is_empty() {
    return Ok(Vec::new());
  }
  Ok(
    client
      .query(
        "SELECT requested.id
           FROM UNNEST($1::text[]) WITH ORDINALITY requested(id, position)
           JOIN runs ON runs.id = requested.id
          WHERE $2 OR runs.status <> 'RUNNING'
          ORDER BY requested.position",
        &[&run_ids, &include_running],
      )?
      .iter()
      .map(|row| row.get(0))
      .collect(),
  )
}

fn count_for_runs<C: GenericClient>(
  client: &mut C,
  table: &str,
  run_ids: &[String],
) -> Result<i64> {
  if run_ids.is_empty() {
    return Ok(0);
  }
  Ok(
    client
      .query_one(
        &format!("SELECT COUNT(*) FROM {table} WHERE run_id = ANY($1)"),
        &[&run_ids],
      )?
      .get(0),
  )
}
