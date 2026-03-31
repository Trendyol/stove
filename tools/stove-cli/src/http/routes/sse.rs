use std::convert::Infallible;
use std::time::Duration;

use axum::extract::State;
use axum::response::sse::{Event, KeepAlive, Sse};
use tokio_stream::StreamExt;
use tokio_stream::wrappers::BroadcastStream;

use crate::http::server::AppState;

/// SSE endpoint that streams dashboard events to connected browser clients.
///
/// Sends a keep-alive comment every 15 seconds to prevent proxies and browsers
/// from closing the connection during long-running tests.
pub async fn sse_handler(
  State(state): State<AppState>,
) -> Sse<impl tokio_stream::Stream<Item = Result<Event, Infallible>>> {
  let rx = state.sse_manager.subscribe();
  let stream = BroadcastStream::new(rx)
    .filter_map(|result| result.ok().map(|data| Ok(Event::default().data(data))));
  Sse::new(stream).keep_alive(
    KeepAlive::new()
      .interval(Duration::from_secs(15))
      .text("keep-alive"),
  )
}
