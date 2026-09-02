use std::sync::atomic::{AtomicU64, Ordering};

use tokio::sync::broadcast;

use crate::ingest::StoredLiveEvent;

/// Manages SSE (Server-Sent Events) broadcasting to connected browser clients.
///
/// Uses `tokio::sync::broadcast` so multiple SSE clients each get their own receiver.
/// Events are JSON-serialized dashboard events.
pub struct SseManager {
  sender: broadcast::Sender<StoredLiveEvent>,
  last_broadcast_id: AtomicU64,
}

impl SseManager {
  #[must_use]
  pub fn new() -> Self {
    let (sender, _) = broadcast::channel(4096);
    Self {
      sender,
      last_broadcast_id: AtomicU64::new(0),
    }
  }

  /// Broadcast a JSON event to all connected SSE clients.
  ///
  /// Ignores `SendError` (no subscribers is fine — nobody is listening yet).
  pub fn broadcast(&self, event: StoredLiveEvent) {
    if self.last_broadcast_id.fetch_max(event.id, Ordering::AcqRel) >= event.id {
      return;
    }
    if let Err(e) = self.sender.send(event) {
      tracing::debug!("No SSE subscribers to broadcast to: {e}");
    }
  }

  /// Create a new receiver for SSE clients to subscribe to.
  #[must_use]
  pub fn subscribe(&self) -> broadcast::Receiver<StoredLiveEvent> {
    self.sender.subscribe()
  }

  #[must_use]
  pub fn last_broadcast_id(&self) -> u64 {
    self.last_broadcast_id.load(Ordering::Acquire)
  }
}

impl Default for SseManager {
  fn default() -> Self {
    Self::new()
  }
}
