use std::convert::Infallible;
use std::sync::Arc;
use std::time::Duration;

use axum::extract::{Query, State};
use axum::http::HeaderMap;
use axum::response::sse::{Event, KeepAlive, Sse};
use serde::Deserialize;
use tokio::sync::{broadcast, mpsc};
use tokio::time::{Instant, timeout};
use tokio_stream::wrappers::ReceiverStream;

use crate::error::{AppError, Result};
use crate::http::server::AppState;
use crate::ingest::StoredLiveEvent;
use crate::sse::manager::LiveNotice;
use crate::storage::repository::Repository;
use crate::storage::repository::replay::{REPLAY_PAGE_BYTES, REPLAY_PAGE_EVENTS, ReplayScope};

type Sender = mpsc::Sender<std::result::Result<Event, Infallible>>;
const REPLAY_TIME: Duration = Duration::from_secs(5);

#[derive(Default, Deserialize)]
pub struct Subscription {
  mode: Option<String>,
  run_id: Option<String>,
  test_id: Option<String>,
  after: Option<u64>,
}

#[utoipa::path(
  get,
  path = "/api/v1/events/stream",
  tag = "events",
  params(
    ("last-event-id" = Option<u64>, Header, description = "Resume after this event; overrides after"),
    ("after" = Option<u64>, Query, description = "Durable cursor for reconnects and subscription changes"),
    ("mode" = Option<String>, Query, description = "Opt in with scoped: all lifecycle events plus selected evidence"),
    ("run_id" = Option<String>, Query, description = "Evidence run in scoped mode"),
    ("test_id" = Option<String>, Query, description = "Evidence test within the selected run")
  ),
  responses((status = 200, description = "Live dashboard event stream", content_type = "text/event-stream"))
)]
pub async fn sse_handler(
  State(state): State<AppState>,
  headers: HeaderMap,
  Query(query): Query<Subscription>,
) -> Result<Sse<impl tokio_stream::Stream<Item = std::result::Result<Event, Infallible>>>> {
  if query.mode.as_deref().is_some_and(|mode| mode != "scoped")
    || (query.test_id.is_some() && query.run_id.is_none())
  {
    return Err(AppError::InvalidEvent(
      "use mode=scoped with an optional run_id and test_id".into(),
    ));
  }
  let scope = query.mode.map(|_| ReplayScope {
    run_id: query.run_id,
    test_id: query.test_id,
  });
  let permit = state
    .repository
    .stream_admission
    .clone()
    .try_acquire_owned()
    .map_err(|_| AppError::Overloaded)?;
  let cursor = match last_event_id(&headers).or(query.after) {
    Some(cursor) => cursor,
    None => {
      state
        .repository
        .read_async(Repository::latest_live_event_id)
        .await?
    }
  };
  let live = state.sse_manager.subscribe();
  let (sender, receiver) = mpsc::channel(1);
  tokio::spawn(async move {
    let _permit = permit;
    forward_events(state.repository, live, cursor, sender, scope).await;
  });
  Ok(
    Sse::new(ReceiverStream::new(receiver)).keep_alive(
      KeepAlive::new()
        .interval(Duration::from_secs(15))
        .text("keep-alive"),
    ),
  )
}

fn last_event_id(headers: &HeaderMap) -> Option<u64> {
  headers
    .get("last-event-id")
    .and_then(|value| value.to_str().ok())
    .and_then(|value| value.parse().ok())
}

async fn send(sender: &Sender, event: Event) -> bool {
  matches!(
    timeout(REPLAY_TIME, sender.send(Ok(event))).await,
    Ok(Ok(()))
  )
}

async fn checkpoint(sender: &Sender, cursor: u64) -> bool {
  send(
    sender,
    Event::default()
      .event("cursor")
      .id(cursor.to_string())
      .data(cursor.to_string()),
  )
  .await
}

async fn resync(sender: &Sender, reason: &str, watermark: u64) -> bool {
  crate::metrics::resync_requested();
  send(
    sender,
    Event::default()
      .event("resync")
      .data(serde_json::json!({ "reason": reason, "watermark": watermark }).to_string()),
  )
  .await;
  false
}

async fn forward_events(
  repository: Arc<Repository>,
  mut live: broadcast::Receiver<LiveNotice>,
  mut cursor: u64,
  sender: Sender,
  scope: Option<ReplayScope>,
) {
  if !replay_available(&repository, &sender, &mut cursor, scope.as_ref()).await {
    return;
  }
  let mut checkpoints = tokio::time::interval(Duration::from_secs(1));
  loop {
    tokio::select! {
      () = sender.closed() => return,
      _ = checkpoints.tick(), if scope.is_some() => {
        if !checkpoint(&sender, cursor).await { return; }
      }
      notice = live.recv() => match notice {
        Ok(notice) if notice.id > cursor => {
          if let Some(cached) = notice.event() {
            let included = scope.as_ref().is_none_or(|scope| cached.scope.as_ref().is_some_and(|event|
              event.includes(scope.run_id.as_deref(), scope.test_id.as_deref())));
            if included && !send(&sender, to_sse_event(&cached.event)).await { return; }
            cursor = notice.id;
          } else if !replay_available(&repository, &sender, &mut cursor, scope.as_ref()).await { return; }
        }
        Ok(_) => {}
        Err(broadcast::error::RecvError::Lagged(_)) => {
          if !replay_available(&repository, &sender, &mut cursor, scope.as_ref()).await { return; }
        }
        Err(broadcast::error::RecvError::Closed) => return,
      }
    }
  }
}

async fn replay_available(
  repository: &Arc<Repository>,
  sender: &Sender,
  cursor: &mut u64,
  scope: Option<&ReplayScope>,
) -> bool {
  let deadline = Instant::now() + REPLAY_TIME;
  let mut events = 0;
  let mut bytes = 0;
  loop {
    let page = match tokio::time::timeout_at(
      deadline,
      repository.replay_page(
        *cursor,
        REPLAY_PAGE_EVENTS,
        REPLAY_PAGE_BYTES,
        scope.cloned(),
      ),
    )
    .await
    {
      Ok(Ok(page)) => page,
      error => {
        tracing::warn!(
          ?error,
          "Durable replay failed; disconnecting without advancing cursor"
        );
        return false;
      }
    };
    if page.deleted_through > *cursor || *cursor > page.watermark {
      return resync(sender, "history_unavailable", page.watermark).await;
    }
    for event in page.events {
      events += 1;
      bytes += event.json.len();
      if events > 10_000 || bytes > 8 * 1024 * 1024 || Instant::now() >= deadline {
        return resync(sender, "replay_budget_exceeded", page.watermark).await;
      }
      if !matches!(
        tokio::time::timeout_at(deadline, send(sender, to_sse_event(&event))).await,
        Ok(true)
      ) {
        return false;
      }
      *cursor = event.id;
    }
    if page.oversized.is_some() {
      return resync(sender, "event_too_large", page.watermark).await;
    }
    if page.exhausted {
      *cursor = page.watermark;
    }
    if *cursor >= page.watermark {
      return scope.is_none() || checkpoint(sender, *cursor).await;
    }
    tokio::task::yield_now().await;
  }
}

fn to_sse_event(event: &StoredLiveEvent) -> Event {
  Event::default().id(event.id.to_string()).data(&event.json)
}
