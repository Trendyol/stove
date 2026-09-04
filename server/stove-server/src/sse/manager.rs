use std::sync::Mutex;

use tokio::sync::{Notify, broadcast};

use crate::ingest::StoredLiveEvent;

/// Manages SSE (Server-Sent Events) broadcasting to connected browser clients.
///
/// Uses `tokio::sync::broadcast` so multiple SSE clients each get their own receiver.
/// Events are JSON-serialized dashboard events.
pub struct SseManager {
  sender: broadcast::Sender<StoredLiveEvent>,
  last_broadcast_id: Mutex<u64>,
  commit_notify: Notify,
}

impl SseManager {
  #[must_use]
  pub fn new() -> Self {
    let (sender, _) = broadcast::channel(4096);
    Self {
      sender,
      last_broadcast_id: Mutex::new(0),
      commit_notify: Notify::new(),
    }
  }

  /// Broadcast a JSON event to all connected SSE clients.
  ///
  /// Ignores `SendError` (no subscribers is fine — nobody is listening yet).
  pub fn broadcast(&self, event: StoredLiveEvent) {
    // Keep the high-water update and send in one critical section. This makes
    // publication monotonic even if callers race, and drops stale events.
    let mut last_broadcast_id = self
      .last_broadcast_id
      .lock()
      .unwrap_or_else(std::sync::PoisonError::into_inner);
    if *last_broadcast_id >= event.id {
      return;
    }
    *last_broadcast_id = event.id;
    if let Err(e) = self.sender.send(event) {
      tracing::debug!("No SSE subscribers to broadcast to: {e}");
    }
  }

  /// Establish the durable cursor before the relay starts. Existing events
  /// remain available for per-client replay but are not rebroadcast as new.
  pub(crate) fn initialize_high_water(&self, event_id: u64) {
    let mut last_broadcast_id = self
      .last_broadcast_id
      .lock()
      .unwrap_or_else(std::sync::PoisonError::into_inner);
    *last_broadcast_id = (*last_broadcast_id).max(event_id);
  }

  /// Wake the durable relay after a successful commit. Notifications may
  /// coalesce because the relay always drains every event after its cursor.
  pub(crate) fn notify_commit(&self) {
    self.commit_notify.notify_one();
  }

  pub(crate) async fn wait_for_commit(&self) {
    self.commit_notify.notified().await;
  }

  /// Create a new receiver for SSE clients to subscribe to.
  #[must_use]
  pub fn subscribe(&self) -> broadcast::Receiver<StoredLiveEvent> {
    self.sender.subscribe()
  }

  #[must_use]
  pub fn last_broadcast_id(&self) -> u64 {
    *self
      .last_broadcast_id
      .lock()
      .unwrap_or_else(std::sync::PoisonError::into_inner)
  }
}

impl Default for SseManager {
  fn default() -> Self {
    Self::new()
  }
}

#[cfg(test)]
mod tests {
  use std::sync::Arc;

  use super::SseManager;
  use crate::ingest::StoredLiveEvent;

  #[tokio::test(flavor = "multi_thread", worker_threads = 2)]
  async fn concurrent_broadcasts_are_monotonic() {
    let manager = Arc::new(SseManager::new());
    let mut receiver = manager.subscribe();
    let high = manager.clone();
    let low = manager.clone();

    let high = tokio::spawn(async move {
      high.broadcast(StoredLiveEvent {
        id: 2,
        json: "two".to_string(),
      });
    });
    let low = tokio::spawn(async move {
      low.broadcast(StoredLiveEvent {
        id: 1,
        json: "one".to_string(),
      });
    });
    high.await.unwrap();
    low.await.unwrap();

    let mut ids = Vec::new();
    while let Ok(event) = receiver.try_recv() {
      ids.push(event.id);
    }
    assert!(ids.windows(2).all(|pair| pair[0] < pair[1]));
    assert_eq!(manager.last_broadcast_id(), 2);
  }

  #[test]
  fn initialized_high_water_drops_restart_replays() {
    let manager = SseManager::new();
    manager.initialize_high_water(41);
    let mut receiver = manager.subscribe();

    manager.broadcast(StoredLiveEvent {
      id: 40,
      json: "stale".to_string(),
    });
    manager.broadcast(StoredLiveEvent {
      id: 42,
      json: "new".to_string(),
    });

    assert_eq!(receiver.try_recv().unwrap().id, 42);
    assert!(receiver.try_recv().is_err());
  }
}
