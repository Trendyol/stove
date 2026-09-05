use std::sync::Arc;

use diesel::QueryableByName;
use diesel::sql_types::BigInt;

use super::{Backend, Repository};
use crate::error::Result;
use crate::ingest::StoredLiveEvent;

pub(crate) const REPLAY_PAGE_EVENTS: usize = 200;
pub(crate) const REPLAY_PAGE_BYTES: usize = 1024 * 1024;

#[derive(Debug)]
pub(crate) struct ReplayPage {
  pub events: Vec<StoredLiveEvent>,
  pub watermark: u64,
  pub deleted_through: u64,
  pub oversized: Option<u64>,
  pub exhausted: bool,
}

#[derive(Clone, Debug, Default)]
pub(crate) struct ReplayScope {
  pub run_id: Option<String>,
  pub test_id: Option<String>,
}

#[derive(QueryableByName)]
pub(super) struct ReplayBounds {
  #[diesel(sql_type = BigInt)]
  pub watermark: i64,
  #[diesel(sql_type = BigInt)]
  pub deleted_through: i64,
}

#[derive(QueryableByName)]
pub(super) struct ReplaySize {
  #[diesel(sql_type = BigInt)]
  pub id: i64,
  #[diesel(sql_type = BigInt)]
  pub bytes: i64,
}

pub(super) const BOUNDS_SQL: &str = "SELECT
  (SELECT COALESCE(MAX(deleted_through), 0) FROM live_event_retention) AS deleted_through,
  (SELECT COALESCE(MAX(cursor), 0) FROM (
    SELECT MAX(id) AS cursor FROM live_events
    UNION ALL SELECT MAX(deleted_through) AS cursor FROM live_event_retention
  ) AS cursors) AS watermark";

/// Determine a safe range using lengths before materializing any payloads.
pub(super) fn page_end(rows: &[ReplaySize], byte_limit: usize) -> (Option<i64>, Option<u64>) {
  let mut bytes = 0_u64;
  let mut end = None;
  for row in rows {
    let size = u64::try_from(row.bytes).unwrap_or(u64::MAX);
    if size > (byte_limit as u64).saturating_sub(bytes) {
      return (
        end,
        end
          .is_none()
          .then(|| u64::try_from(row.id).unwrap_or(u64::MAX)),
      );
    }
    bytes += size;
    end = Some(row.id);
  }
  (end, None)
}

impl Repository {
  pub(crate) async fn replay_page(
    self: &Arc<Self>,
    after: u64,
    events: usize,
    bytes: usize,
    scope: Option<ReplayScope>,
  ) -> Result<ReplayPage> {
    let repository = self.clone();
    crate::blocking::admitted(
      &self.replay_admission,
      &crate::metrics::REPLAY,
      0,
      "replay",
      move || match &repository.backend {
        Backend::Sqlite(sqlite) => sqlite.replay_page(
          after,
          events.min(REPLAY_PAGE_EVENTS),
          bytes.min(REPLAY_PAGE_BYTES),
          scope.as_ref(),
        ),
        Backend::Postgres(postgres) => postgres.replay_page(
          after,
          events.min(REPLAY_PAGE_EVENTS),
          bytes.min(REPLAY_PAGE_BYTES),
          scope.as_ref(),
        ),
      },
    )
    .await
  }

  pub(crate) async fn read_async<T, F>(self: &Arc<Self>, operation: F) -> Result<T>
  where
    T: Send + 'static,
    F: FnOnce(&Self) -> Result<T> + Send + 'static,
  {
    let repository = self.clone();
    crate::blocking::admitted(
      &self.read_admission,
      &crate::metrics::READ,
      0,
      "read",
      move || operation(&repository),
    )
    .await
  }
}

#[cfg(test)]
mod tests {
  use super::*;
  use crate::error::AppError;

  #[test]
  fn replay_lengths_stop_before_loading_an_oversized_payload() {
    let rows = [
      ReplaySize { id: 1, bytes: 10 },
      ReplaySize { id: 3, bytes: 20 },
    ];
    assert_eq!(page_end(&rows, 30), (Some(3), None));
    assert_eq!(page_end(&rows, 29), (Some(1), None));
    assert_eq!(page_end(&rows, 9), (None, Some(1)));
    assert_eq!(page_end(&[], 0), (None, None));
  }

  #[tokio::test]
  async fn cancelled_reads_keep_admission_until_blocking_work_finishes() {
    let mut repository = Repository::connect_sqlite(":memory:", 0).unwrap();
    repository.configure_admission(1, 1, 1).unwrap();
    let repository = Arc::new(repository);
    let (started, waiting) = tokio::sync::oneshot::channel();
    let (release, blocked) = std::sync::mpsc::channel();
    let reader = repository.clone();
    let task = tokio::spawn(async move {
      reader
        .read_async(move |_| {
          started.send(()).unwrap();
          blocked.recv().unwrap();
          Ok(())
        })
        .await
    });
    waiting.await.unwrap();
    task.abort();
    assert!(matches!(
      repository.read_async(|_| Ok(())).await,
      Err(AppError::Overloaded)
    ));
    release.send(()).unwrap();
    tokio::time::timeout(std::time::Duration::from_secs(1), async {
      loop {
        if repository.read_async(|_| Ok(())).await.is_ok() {
          break;
        }
        tokio::task::yield_now().await;
      }
    })
    .await
    .unwrap();
    let _permit = repository
      .replay_admission
      .clone()
      .acquire_owned()
      .await
      .unwrap();
    assert!(matches!(
      repository.replay_page(0, 200, 1024, None).await,
      Err(AppError::Overloaded)
    ));
  }
}
