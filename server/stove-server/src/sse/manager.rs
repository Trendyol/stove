use std::collections::VecDeque;
use std::sync::{Arc, Mutex, Weak};

use serde::Deserialize;
use tokio::sync::{Notify, broadcast};

use crate::ingest::StoredLiveEvent;

const CACHE_BYTES: usize = 8 * 1024 * 1024;
const CACHE_EVENTS: usize = 2_000;

#[derive(Debug, Deserialize)]
pub(crate) struct LiveScope {
  pub run_id: String,
  pub event_type: String,
  pub payload: TestScope,
}

#[derive(Debug, Deserialize)]
pub(crate) struct TestScope {
  pub test_id: Option<String>,
}

impl LiveScope {
  pub(crate) fn includes(&self, run: Option<&str>, test: Option<&str>) -> bool {
    matches!(
      self.event_type.as_str(),
      "run_started" | "run_ended" | "test_started" | "test_ended"
    ) || (run == Some(self.run_id.as_str())
      && test.is_none_or(|test| self.payload.test_id.as_deref() == Some(test)))
  }
}

#[derive(Debug)]
pub struct CachedLiveEvent {
  pub event: StoredLiveEvent,
  pub(crate) scope: Option<LiveScope>,
}

/// The broadcast ring holds only weak references, never a second unbounded payload cache.
#[derive(Clone, Debug)]
pub struct LiveNotice {
  pub id: u64,
  payload: Weak<CachedLiveEvent>,
}

impl LiveNotice {
  #[must_use]
  pub fn event(&self) -> Option<Arc<CachedLiveEvent>> {
    self.payload.upgrade()
  }
}

#[derive(Default)]
struct Cache {
  last_id: u64,
  bytes: usize,
  events: VecDeque<Arc<CachedLiveEvent>>,
}

pub(crate) struct SseMetrics {
  pub events: usize,
  pub bytes: usize,
  pub subscribers: usize,
  pub cursor: u64,
}

pub struct SseManager {
  sender: broadcast::Sender<LiveNotice>,
  cache: Mutex<Cache>,
  commit_notify: Notify,
}

impl SseManager {
  #[must_use]
  pub fn new() -> Self {
    let (sender, _) = broadcast::channel(4096);
    Self {
      sender,
      cache: Mutex::new(Cache::default()),
      commit_notify: Notify::new(),
    }
  }

  fn cache(&self) -> std::sync::MutexGuard<'_, Cache> {
    self
      .cache
      .lock()
      .unwrap_or_else(std::sync::PoisonError::into_inner)
  }

  pub(crate) fn metrics(&self) -> SseMetrics {
    let cache = self.cache();
    SseMetrics {
      events: cache.events.len(),
      bytes: cache.bytes,
      subscribers: self.sender.receiver_count(),
      cursor: cache.last_id,
    }
  }

  pub fn broadcast(&self, event: StoredLiveEvent) {
    let scope = serde_json::from_str(&event.json).ok();
    let payload = Arc::new(CachedLiveEvent { event, scope });
    let mut cache = self.cache();
    if cache.last_id >= payload.event.id {
      return;
    }
    cache.last_id = payload.event.id;
    let notice = LiveNotice {
      id: payload.event.id,
      payload: Arc::downgrade(&payload),
    };
    if payload.event.json.len() <= CACHE_BYTES {
      cache.bytes += payload.event.json.len();
      cache.events.push_back(payload);
      while cache.bytes > CACHE_BYTES || cache.events.len() > CACHE_EVENTS {
        if let Some(expired) = cache.events.pop_front() {
          cache.bytes -= expired.event.json.len();
        }
      }
    }
    let _ = self.sender.send(notice);
  }

  /// Missing or oversized history forces clients through the durable replay/resync path.
  pub(crate) fn broadcast_cursor(&self, id: u64) {
    let mut cache = self.cache();
    if cache.last_id >= id {
      return;
    }
    cache.last_id = id;
    let _ = self.sender.send(LiveNotice {
      id,
      payload: Weak::new(),
    });
  }

  pub(crate) fn initialize_high_water(&self, id: u64) {
    let mut cache = self.cache();
    cache.last_id = cache.last_id.max(id);
  }

  pub(crate) fn notify_commit(&self) {
    self.commit_notify.notify_one();
  }
  pub(crate) async fn wait_for_commit(&self) {
    self.commit_notify.notified().await;
  }
  #[must_use]
  pub fn subscribe(&self) -> broadcast::Receiver<LiveNotice> {
    self.sender.subscribe()
  }
  #[must_use]
  pub fn last_broadcast_id(&self) -> u64 {
    self.cache().last_id
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
  fn payload_cache_is_shared_and_evicted_by_bytes() {
    let manager = SseManager::new();
    let mut first = manager.subscribe();
    let mut second = manager.subscribe();
    manager.broadcast(StoredLiveEvent {
      id: 1,
      json: "x".repeat(super::CACHE_BYTES / 2),
    });
    let notice = first.try_recv().unwrap();
    assert!(Arc::ptr_eq(
      &notice.event().unwrap(),
      &second.try_recv().unwrap().event().unwrap()
    ));
    for id in 2..=3 {
      manager.broadcast(StoredLiveEvent {
        id,
        json: "x".repeat(super::CACHE_BYTES / 2),
      });
    }
    assert!(notice.event().is_none());
    let cache = manager.cache.lock().unwrap();
    assert_eq!(cache.bytes, super::CACHE_BYTES);
    assert_eq!(cache.events.len(), 2);
  }

  #[test]
  fn payload_cache_is_evicted_by_count_and_cursor_notices_force_replay() {
    let manager = SseManager::new();
    let mut receiver = manager.subscribe();
    manager.broadcast(StoredLiveEvent {
      id: 1,
      json: "{}".into(),
    });
    let notice = receiver.try_recv().unwrap();
    for id in 2..=2_001 {
      manager.broadcast(StoredLiveEvent {
        id,
        json: "{}".into(),
      });
    }
    assert!(notice.event().is_none());
    assert_eq!(
      manager.cache.lock().unwrap().events.len(),
      super::CACHE_EVENTS
    );
    let mut receiver = manager.subscribe();
    manager.broadcast_cursor(2_002);
    let missing = receiver.try_recv().unwrap();
    assert_eq!(missing.id, 2_002);
    assert!(missing.event().is_none());
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
