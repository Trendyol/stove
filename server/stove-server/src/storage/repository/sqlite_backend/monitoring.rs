use super::SqliteBackend;
use crate::storage::metrics::{Capacity, DatabaseDiagnostics, HealthSample};
use diesel::connection::SimpleConnection;

impl SqliteBackend {
  pub(in crate::storage::repository) fn capacity(&self) -> Vec<Capacity> {
    [
      ("read", self.read.try_lock().is_ok()),
      ("write", self.write.try_lock().is_ok()),
      ("explorer", self.explorer.try_lock().is_ok()),
    ]
    .into_iter()
    .map(|(role, idle)| Capacity::new(role, 1, 1, u32::from(idle)))
    .collect()
  }
  pub(in crate::storage::repository) fn probe_roles(&self) -> Vec<HealthSample> {
    let mut checks = Vec::new();
    for (role, connection) in [("read", &self.read), ("write", &self.write)] {
      checks.push(HealthSample::probe(role, || {
        connection.try_lock().is_ok_and(|mut db| {
          db.conn()
            .batch_execute("SELECT id FROM runs LIMIT 1")
            .is_ok()
        })
      }));
    }
    checks.push(HealthSample::probe("explorer", || {
      self.explorer.try_lock().is_ok_and(|connection| {
        connection
          .query_row(
            "SELECT count(*) FROM (SELECT id FROM runs LIMIT 1)",
            [],
            |row| row.get::<_, i64>(0),
          )
          .is_ok()
      })
    }));
    checks
  }
  pub(in crate::storage::repository) fn diagnostics(
    &self,
  ) -> std::result::Result<DatabaseDiagnostics, String> {
    let connection = self
      .monitor
      .lock()
      .map_err(|_| "SQLite diagnostics connection unavailable")?;
    let read = |pragma: &str| {
      connection
        .query_row(pragma, [], |row| row.get::<_, i64>(0))
        .map_err(|_| "SQLite diagnostics query failed".to_string())
        .and_then(|value| u64::try_from(value).map_err(|_| "Invalid SQLite page statistic".into()))
    };
    let pages = read("PRAGMA page_count")?;
    let page_size = read("PRAGMA page_size")?;
    let free_pages = read("PRAGMA freelist_count")?;
    let mut result = DatabaseDiagnostics {
      database_bytes: Some(pages.saturating_mul(page_size)),
      free_page_bytes: Some(free_pages.saturating_mul(page_size)),
      ..DatabaseDiagnostics::default()
    };
    if let Some(path) = &self.path {
      result.wal_bytes = match std::fs::metadata(format!("{path}-wal")) {
        Ok(metadata) => Some(metadata.len()),
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => Some(0),
        Err(_) => {
          result.notes.push("WAL file size unavailable.".into());
          None
        }
      };
      result.disk_available_bytes = fs2::available_space(path).ok();
      if result.disk_available_bytes.is_none() {
        result.notes.push("Filesystem capacity unavailable.".into());
      }
      // NOOP only reads WAL/checkpoint counters; it never checkpoints pages.
      // Supported by the bundled SQLite used by both database drivers.
      match connection.query_row("PRAGMA wal_checkpoint(NOOP)", [], |row| {
        Ok((
          row.get::<_, i64>(0)?,
          row.get::<_, i64>(1)?,
          row.get::<_, i64>(2)?,
        ))
      }) {
        Ok((busy, frames, checkpointed)) if frames >= 0 => {
          result.checkpoint_probe_up = Some(true);
          result.checkpoint_probe_busy = Some(busy);
          result.wal_frames = Some(frames);
          result.checkpointed_frames = Some(checkpointed);
        }
        Ok(_) => result
          .notes
          .push("Checkpoint metrics unavailable outside WAL mode.".into()),
        Err(_) => {
          result.checkpoint_probe_up = Some(false);
          result.notes.push("Checkpoint counters unavailable.".into());
        }
      }
    } else {
      result
        .notes
        .push("In-memory SQLite has no WAL file or filesystem capacity.".into());
    }
    Ok(result)
  }
}

#[cfg(test)]
mod tests {
  use super::*;
  use crate::storage::repository::Repository;
  use std::time::Instant;
  #[test]
  fn file_diagnostics_report_size_capacity_and_checkpoint_progress() {
    let directory = tempfile::tempdir().unwrap();
    let path = directory.path().join("metrics.db");
    let backend = SqliteBackend::connect(path.to_str().unwrap()).unwrap();
    let diagnostics = backend.diagnostics().unwrap();
    assert!(diagnostics.database_bytes.unwrap() > 0);
    assert!(diagnostics.disk_available_bytes.unwrap() > 0);
    assert!(diagnostics.wal_bytes.is_some());
    assert!(diagnostics.checkpointed_frames.is_some());
    assert_eq!(diagnostics.checkpoint_probe_up, Some(true));
    assert!(backend.probe_roles().iter().all(|probe| probe.healthy));
  }
  #[test]
  fn in_memory_database_omits_filesystem_statistics() {
    let backend = SqliteBackend::connect(":memory:").unwrap();
    let diagnostics = backend.diagnostics().unwrap();
    assert!(diagnostics.database_bytes.unwrap() > 0);
    assert_eq!(diagnostics.disk_available_bytes, None);
    assert_eq!(diagnostics.wal_bytes, None);
  }
  #[test]
  fn diagnostics_do_not_checkpoint_pending_wal_pages() {
    let directory = tempfile::tempdir().unwrap();
    let path = directory.path().join("metrics.db");
    let backend = SqliteBackend::connect(path.to_str().unwrap()).unwrap();
    {
      let mut writer = backend.write.lock().unwrap();
      writer.conn().batch_execute(
        "PRAGMA wal_checkpoint(TRUNCATE); PRAGMA wal_autocheckpoint=0; CREATE TABLE metrics_probe (id INTEGER); INSERT INTO metrics_probe VALUES (1)"
      ).unwrap();
    }
    let before = backend.diagnostics().unwrap();
    assert!(before.wal_frames.unwrap() > before.checkpointed_frames.unwrap());
    let after = backend.diagnostics().unwrap();
    assert_eq!(before.wal_frames, after.wal_frames);
    assert_eq!(before.checkpointed_frames, after.checkpointed_frames);
  }
  #[test]
  fn held_writer_does_not_block_capacity_or_scrapes() {
    let repository = Repository::connect_sqlite(":memory:", 1).unwrap();
    let crate::storage::repository::Backend::Sqlite(backend) = &repository.backend else {
      unreachable!()
    };
    let held = backend.write.lock().unwrap();
    repository.metrics.sample(backend.capacity());
    let started = Instant::now();
    let text = repository.metrics.prometheus();
    assert!(started.elapsed() < std::time::Duration::from_secs(1));
    assert!(
      text
        .lines()
        .any(|line| line.starts_with("stove_db_capacity_busy{")
          && line.contains("role=\"write\"")
          && line.ends_with(" 1"))
    );
    drop(held);
  }
}
