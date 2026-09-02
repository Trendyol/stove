use diesel::prelude::*;

use super::PostgresBackend;
use super::writes::{prune_app, retention_on};
use crate::error::Result;
use crate::storage::models::{EvidenceCounts, PurgePreview, PurgeResult, StorageStats};
use crate::storage::repository::admin::{
  PurgeCandidate, select_purge_candidates, select_requested_run_ids,
};
use crate::storage::schema::postgres::{
  dashboard_settings, entries, mock_interactions, mock_warnings, runs, snapshots, spans, tests,
};

impl PostgresBackend {
  pub fn retention_runs_per_app(&self) -> Result<usize> {
    retention_on(&mut self.lock_read())
  }

  pub fn storage_stats(&self) -> Result<StorageStats> {
    let mut conn = self.lock_read();
    let retention = retention_on(&mut conn)?;
    Ok(StorageStats {
      backend: "postgresql".to_string(),
      retention_runs_per_app: retention,
      runs: runs::table.count().get_result(&mut *conn)?,
      running_runs: runs::table
        .filter(runs::status.eq("RUNNING"))
        .count()
        .get_result(&mut *conn)?,
      evidence: all_evidence_counts(&mut conn)?,
    })
  }

  pub fn update_retention(&self, retention: usize) -> Result<()> {
    self.lock_write().transaction(|conn| {
      diesel::insert_into(dashboard_settings::table)
        .values((
          dashboard_settings::setting_key.eq("retention_runs_per_app"),
          dashboard_settings::setting_value.eq(retention.to_string()),
        ))
        .on_conflict(dashboard_settings::setting_key)
        .do_update()
        .set((
          dashboard_settings::setting_value.eq(retention.to_string()),
          dashboard_settings::updated_at.eq(diesel::dsl::now),
        ))
        .execute(conn)?;
      if retention > 0 {
        let apps = runs::table
          .select(runs::app_name)
          .distinct()
          .load::<String>(conn)?;
        for app_name in apps {
          prune_app(conn, &app_name, retention)?;
        }
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
    let mut conn = self.lock_read();
    let candidates = runs::table
      .order((runs::started_at, runs::id))
      .select((runs::id, runs::app_name, runs::started_at, runs::status))
      .load::<(String, String, String, String)>(&mut *conn)?
      .into_iter()
      .map(PurgeCandidate::from);
    let run_ids = select_purge_candidates(candidates, app_name, older_than, include_running);
    let evidence = evidence_counts(&mut conn, &run_ids)?;
    Ok(PurgePreview {
      run_count: run_ids.len(),
      run_ids,
      evidence,
    })
  }

  pub fn purge_runs(&self, run_ids: &[String], include_running: bool) -> Result<PurgeResult> {
    self.lock_write().transaction(|conn| {
      let selected = select_requested_runs(conn, run_ids, include_running)?;
      let evidence = evidence_counts(conn, &selected)?;
      diesel::delete(runs::table.filter(runs::id.eq_any(&selected))).execute(conn)?;
      Ok(PurgeResult {
        purged_runs: selected.len(),
        purged_run_ids: selected,
        evidence,
      })
    })
  }
}

fn all_evidence_counts(conn: &mut PgConnection) -> Result<EvidenceCounts> {
  Ok(EvidenceCounts {
    tests: tests::table.count().get_result(conn)?,
    entries: entries::table.count().get_result(conn)?,
    spans: spans::table.count().get_result(conn)?,
    snapshots: snapshots::table.count().get_result(conn)?,
    mock_interactions: mock_interactions::table.count().get_result(conn)?,
    mock_warnings: mock_warnings::table.count().get_result(conn)?,
  })
}

fn evidence_counts(conn: &mut PgConnection, run_ids: &[String]) -> Result<EvidenceCounts> {
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
  conn: &mut PgConnection,
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
