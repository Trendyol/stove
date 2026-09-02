use diesel::prelude::*;

use super::super::admin::{PurgeCandidate, select_purge_candidates, select_requested_run_ids};
use super::SqliteBackend;
use super::writes::{delete_runs_on, prune_completed_runs_for_app_on};
use crate::error::Result;
use crate::storage::models::{EvidenceCounts, PurgePreview, PurgeResult, StorageStats};
use crate::storage::schema::sqlite::{
  entries, mock_interactions, mock_warnings, runs, snapshots, spans, tests,
};

impl SqliteBackend {
  pub fn storage_stats(&self, retention_runs_per_app: usize) -> Result<StorageStats> {
    let mut db = self.lock_read();
    let conn = db.conn();
    Ok(StorageStats {
      backend: "sqlite".to_string(),
      retention_runs_per_app,
      runs: runs::table.count().get_result(conn)?,
      running_runs: runs::table
        .filter(runs::status.eq("RUNNING"))
        .count()
        .get_result(conn)?,
      evidence: all_evidence_counts(conn)?,
    })
  }

  pub fn update_retention(&self, runs_per_app: usize) -> Result<()> {
    if runs_per_app == 0 {
      return Ok(());
    }
    let mut db = self.lock_write();
    db.conn().transaction(|conn| {
      let apps = runs::table
        .select(runs::app_name)
        .distinct()
        .load::<String>(conn)?;
      for app_name in apps {
        prune_completed_runs_for_app_on(conn, &app_name, runs_per_app)?;
      }
      Ok(())
    })
  }

  pub fn preview_purge(
    &self,
    app_name: Option<&str>,
    older_than: Option<&str>,
    include_running: bool,
  ) -> Result<PurgePreview> {
    let mut db = self.lock_read();
    let conn = db.conn();
    let candidates = runs::table
      .order((runs::started_at, runs::id))
      .select((runs::id, runs::app_name, runs::started_at, runs::status))
      .load::<(String, String, String, String)>(conn)?
      .into_iter()
      .map(PurgeCandidate::from);
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
    db.conn().transaction(|conn| {
      let selected = select_requested_runs(conn, run_ids, include_running)?;
      let evidence = evidence_counts(conn, &selected)?;
      delete_runs_on(conn, &selected)?;
      Ok(PurgeResult {
        purged_runs: selected.len(),
        purged_run_ids: selected,
        evidence,
      })
    })
  }
}

fn all_evidence_counts(conn: &mut SqliteConnection) -> Result<EvidenceCounts> {
  Ok(EvidenceCounts {
    tests: tests::table.count().get_result(conn)?,
    entries: entries::table.count().get_result(conn)?,
    spans: spans::table.count().get_result(conn)?,
    snapshots: snapshots::table.count().get_result(conn)?,
    mock_interactions: mock_interactions::table.count().get_result(conn)?,
    mock_warnings: mock_warnings::table.count().get_result(conn)?,
  })
}

fn evidence_counts(conn: &mut SqliteConnection, run_ids: &[String]) -> Result<EvidenceCounts> {
  if run_ids.is_empty() {
    return Ok(EvidenceCounts::default());
  }
  Ok(EvidenceCounts {
    tests: tests::table
      .filter(tests::run_id.eq_any(run_ids))
      .count()
      .get_result(conn)?,
    entries: entries::table
      .filter(entries::run_id.eq_any(run_ids))
      .count()
      .get_result(conn)?,
    spans: spans::table
      .filter(spans::run_id.eq_any(run_ids))
      .count()
      .get_result(conn)?,
    snapshots: snapshots::table
      .filter(snapshots::run_id.eq_any(run_ids))
      .count()
      .get_result(conn)?,
    mock_interactions: mock_interactions::table
      .filter(mock_interactions::run_id.eq_any(run_ids))
      .count()
      .get_result(conn)?,
    mock_warnings: mock_warnings::table
      .filter(mock_warnings::run_id.eq_any(run_ids))
      .count()
      .get_result(conn)?,
  })
}

fn select_requested_runs(
  conn: &mut SqliteConnection,
  requested: &[String],
  include_running: bool,
) -> Result<Vec<String>> {
  if requested.is_empty() {
    return Ok(Vec::new());
  }
  let available = runs::table
    .filter(runs::id.eq_any(requested))
    .select((runs::id, runs::status))
    .load::<(String, String)>(conn)?;
  Ok(select_requested_run_ids(
    requested,
    available,
    include_running,
  ))
}
