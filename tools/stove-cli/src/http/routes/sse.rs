use std::convert::Infallible;
use std::sync::Arc;
use std::time::Duration;

use axum::extract::State;
use axum::http::HeaderMap;
use axum::response::sse::{Event, KeepAlive, Sse};
use tokio::sync::broadcast::Receiver;
use tokio::sync::broadcast::error::RecvError;
use tokio::sync::mpsc;
use tokio::sync::mpsc::Sender;
use tokio_stream::wrappers::ReceiverStream;

use crate::http::server::AppState;
use crate::ingest::StoredLiveEvent;
use crate::storage::repository::Repository;

const REPLAY_PAGE_SIZE: usize = 1_000;
const CLIENT_BUFFER_SIZE: usize = 256;

/// SSE endpoint that streams dashboard events to connected browser clients.
///
/// Sends a keep-alive comment every 15 seconds to prevent proxies and browsers
/// from closing the connection during long-running tests.
pub async fn sse_handler(
  State(state): State<AppState>,
  headers: HeaderMap,
) -> Sse<impl tokio_stream::Stream<Item = Result<Event, Infallible>>> {
  // Read before subscribing, then replay after subscribing. An event committed
  // across that boundary is therefore present in either the durable replay or
  // this receiver, and the cursor filters it if it appears in both.
  let cursor = last_event_id(&headers).unwrap_or_else(|| {
    state
      .repository
      .latest_live_event_id()
      .unwrap_or_else(|error| {
        tracing::warn!(%error, "Failed to establish the SSE cursor");
        state.sse_manager.last_broadcast_id()
      })
  });
  let live = state.sse_manager.subscribe();
  let repository = state.repository.clone();
  let (sender, receiver) = mpsc::channel(CLIENT_BUFFER_SIZE);
  tokio::spawn(forward_events(repository, live, cursor, sender));

  Sse::new(ReceiverStream::new(receiver)).keep_alive(
    KeepAlive::new()
      .interval(Duration::from_secs(15))
      .text("keep-alive"),
  )
}

fn last_event_id(headers: &HeaderMap) -> Option<u64> {
  headers
    .get("last-event-id")
    .and_then(|value| value.to_str().ok())
    .and_then(|value| value.parse().ok())
}

async fn forward_events(
  repository: Arc<Repository>,
  mut live: Receiver<StoredLiveEvent>,
  mut cursor: u64,
  sender: Sender<Result<Event, Infallible>>,
) {
  if !replay_available(repository.as_ref(), &sender, &mut cursor).await {
    return;
  }

  loop {
    match live.recv().await {
      Ok(event) if event.id > cursor => {
        cursor = event.id;
        if sender.send(Ok(to_sse_event(event))).await.is_err() {
          return;
        }
      }
      Ok(_) => {}
      Err(RecvError::Lagged(_)) => {
        if !replay_available(repository.as_ref(), &sender, &mut cursor).await {
          return;
        }
      }
      Err(RecvError::Closed) => return,
    }
  }
}

async fn replay_available(
  repository: &Repository,
  sender: &Sender<Result<Event, Infallible>>,
  cursor: &mut u64,
) -> bool {
  loop {
    let events = match repository.live_events_after(*cursor, REPLAY_PAGE_SIZE) {
      Ok(events) => events,
      Err(error) => {
        tracing::warn!(%error, "Failed to replay durable dashboard live events");
        return true;
      }
    };
    let page_is_full = events.len() == REPLAY_PAGE_SIZE;
    for event in events {
      *cursor = event.id;
      if sender.send(Ok(to_sse_event(event))).await.is_err() {
        return false;
      }
    }
    if !page_is_full {
      return true;
    }
  }
}

fn to_sse_event(event: StoredLiveEvent) -> Event {
  Event::default().id(event.id.to_string()).data(event.json)
}
