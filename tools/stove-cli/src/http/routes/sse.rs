use std::convert::Infallible;

use axum::extract::State;
use axum::response::sse::{Event, Sse};
use tokio_stream::StreamExt;
use tokio_stream::wrappers::BroadcastStream;

use crate::http::server::AppState;

/// SSE endpoint that streams portal events to connected browser clients.
pub async fn sse_handler(
  State(state): State<AppState>,
) -> Sse<impl tokio_stream::Stream<Item = Result<Event, Infallible>>> {
  let rx = state.sse_manager.subscribe();
  let stream = BroadcastStream::new(rx)
    .filter_map(|result| result.ok().map(|data| Ok(Event::default().data(data))));
  Sse::new(stream)
}
