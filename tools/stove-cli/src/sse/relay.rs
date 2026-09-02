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
  tokio::spawn(async move {
    let mut cursor = repository.latest_live_event_id().unwrap_or(0);
    let mut notifications = repository.subscribe_live_event_notifications();
    let mut interval = tokio::time::interval(POLL_INTERVAL);

    loop {
      tokio::select! {
        _ = interval.tick() => {}
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
