use tokio::sync::broadcast;

/// Manages SSE (Server-Sent Events) broadcasting to connected browser clients.
///
/// Uses `tokio::sync::broadcast` so multiple SSE clients each get their own receiver.
/// Events are JSON-serialized portal events.
pub struct SseManager {
  sender: broadcast::Sender<String>,
}

impl SseManager {
  #[must_use]
  pub fn new() -> Self {
    let (sender, _) = broadcast::channel(4096);
    Self { sender }
  }

  /// Broadcast a JSON event to all connected SSE clients.
  ///
  /// Ignores `SendError` (no subscribers is fine — nobody is listening yet).
  pub fn broadcast(&self, json: &str) {
    if let Err(e) = self.sender.send(json.to_string()) {
      tracing::debug!("No SSE subscribers to broadcast to: {e}");
    }
  }

  /// Create a new receiver for SSE clients to subscribe to.
  #[must_use]
  pub fn subscribe(&self) -> broadcast::Receiver<String> {
    self.sender.subscribe()
  }
}

impl Default for SseManager {
  fn default() -> Self {
    Self::new()
  }
}
