use std::sync::Arc;
use std::time::Duration;

use tokio::task::JoinHandle;
use tracing::warn;

use super::manager::SseManager;
use crate::error::Result;
use crate::storage::repository::Repository;

const PAGE_SIZE: usize = 1_000;
const POLL_INTERVAL: Duration = Duration::from_millis(250);

/// Tails the durable live-event log and fans every committed event out to this pod's clients.
/// Polling is the correctness mechanism; `PostgreSQL` notifications only shorten wake-up latency.
pub fn spawn(repository: Arc<Repository>, manager: Arc<SseManager>) -> JoinHandle<()> {
  // Capture the durable cursor before the task can be delayed by scheduling.
  // Starting at the current tail avoids replaying the retained log after a
  // process restart; reconnecting clients replay from their own cursor.
  let cursor = repository.latest_live_event_id().unwrap_or_else(|error| {
    warn!(%error, "Failed to establish durable live-event relay cursor; replaying from zero");
    0
  });
  manager.initialize_high_water(cursor);

  tokio::spawn(async move {
    let mut cursor = cursor;
    let mut notifications = repository.subscribe_live_event_notifications();
    let mut interval = tokio::time::interval(POLL_INTERVAL);

    loop {
      tokio::select! {
        _ = interval.tick() => {}
        () = manager.wait_for_commit() => {}
        notification = notifications.recv() => {
          if notification.is_none() {
            // SQLite and failed PostgreSQL listeners use periodic polling only.
            interval.tick().await;
          }
        }
      }

      if let Err(error) = broadcast_available(&repository, &manager, &mut cursor) {
        warn!(%error, "Failed to read durable dashboard live events");
      }
    }
  })
}

pub(crate) fn broadcast_available(
  repository: &Repository,
  manager: &SseManager,
  cursor: &mut u64,
) -> Result<()> {
  loop {
    let events = repository.live_events_after(*cursor, PAGE_SIZE)?;
    let page_is_full = events.len() == PAGE_SIZE;
    for event in events {
      *cursor = event.id;
      manager.broadcast(event);
    }
    if !page_is_full {
      return Ok(());
    }
  }
}

#[cfg(test)]
mod tests {
  use std::sync::Arc;
  use std::time::Duration;

  use super::spawn;
  use crate::ingest::EventIngestor;
  use crate::proto;
  use crate::sse::manager::SseManager;
  use crate::storage::repository::Repository;

  fn run_started(run_id: &str, event_id: &str) -> proto::DashboardEvent {
    proto::DashboardEvent {
      run_id: run_id.to_string(),
      event_id: event_id.to_string(),
      sequence: 1,
      event: Some(proto::dashboard_event::Event::RunStarted(
        proto::RunStartedEvent {
          timestamp: Some(prost_types::Timestamp {
            seconds: 1_704_067_200,
            nanos: 0,
          }),
          app_name: "relay-test".to_string(),
          ..Default::default()
        },
      )),
    }
  }

  #[tokio::test(flavor = "multi_thread", worker_threads = 2)]
  async fn restart_broadcasts_only_events_after_the_initialized_cursor() {
    let directory = tempfile::tempdir().unwrap();
    let database = directory.path().join("relay.db");
    let repository = Arc::new(
      Repository::connect_sqlite(database.to_str().unwrap(), 0).expect("database should open"),
    );
    let old_manager = Arc::new(SseManager::new());
    EventIngestor::new(repository.clone(), old_manager)
      .ingest(&run_started("old-run", "old-event"))
      .unwrap();

    let manager = Arc::new(SseManager::new());
    let relay = spawn(repository.clone(), manager.clone());
    let mut receiver = manager.subscribe();
    assert_eq!(manager.last_broadcast_id(), 1);

    EventIngestor::new(repository, manager)
      .ingest(&run_started("new-run", "new-event"))
      .unwrap();

    let event = tokio::time::timeout(Duration::from_secs(1), receiver.recv())
      .await
      .expect("new event should be published")
      .unwrap();
    assert_eq!(event.id, 2);
    assert!(receiver.try_recv().is_err());
    relay.abort();
  }
}
