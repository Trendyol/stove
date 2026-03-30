use std::sync::Arc;
use std::time::Duration;

use serde::Serialize;
use tokio::sync::{mpsc, oneshot};
use tracing::warn;

use crate::error::{AppError, Result};
use crate::storage::models::{NewEntry, NewSpan};
use crate::storage::repository::Repository;

pub const DEFAULT_MAX_BATCH_SIZE: usize = 20;
pub const DEFAULT_MAX_BATCH_DELAY: Duration = Duration::from_secs(5);

#[derive(Clone, Debug)]
pub enum PersistedPortalEvent {
  RunStarted {
    run_id: String,
    app_name: String,
    started_at: String,
    systems: Vec<String>,
  },
  RunEnded {
    run_id: String,
    ended_at: String,
    total_tests: i32,
    passed: i32,
    failed: i32,
    duration_ms: i64,
  },
  TestStarted {
    run_id: String,
    test_id: String,
    test_name: String,
    spec_name: String,
    started_at: String,
  },
  TestEnded {
    run_id: String,
    test_id: String,
    status: String,
    duration_ms: i64,
    error: Option<String>,
    ended_at: String,
  },
  EntryRecorded(NewEntry),
  SpanRecorded(NewSpan),
  Snapshot {
    run_id: String,
    test_id: String,
    system: String,
    state_json: String,
    summary: String,
  },
}

#[derive(Clone, Debug, Serialize)]
pub struct LivePortalEvent {
  pub seq: u64,
  pub run_id: String,
  pub event_type: String,
  pub payload: LivePortalPayload,
}

impl LivePortalEvent {
  #[must_use]
  pub fn with_seq(mut self, seq: u64) -> Self {
    self.seq = seq;
    let temp_id = live_record_id(seq);
    match &mut self.payload {
      LivePortalPayload::EntryRecorded(payload) => payload.id = temp_id,
      LivePortalPayload::SpanRecorded(payload) => payload.id = temp_id,
      LivePortalPayload::Snapshot(payload) => payload.id = temp_id,
      LivePortalPayload::RunStarted(_)
      | LivePortalPayload::RunEnded(_)
      | LivePortalPayload::TestStarted(_)
      | LivePortalPayload::TestEnded(_) => {}
    }
    self
  }
}

#[derive(Clone, Debug, Serialize)]
#[serde(untagged)]
pub enum LivePortalPayload {
  RunStarted(LiveRunStartedPayload),
  RunEnded(LiveRunEndedPayload),
  TestStarted(LiveTestStartedPayload),
  TestEnded(LiveTestEndedPayload),
  EntryRecorded(LiveEntryRecordedPayload),
  SpanRecorded(LiveSpanRecordedPayload),
  Snapshot(LiveSnapshotPayload),
}

#[derive(Clone, Debug, Serialize)]
pub struct LiveRunStartedPayload {
  pub app_name: String,
  pub started_at: String,
  pub systems: Vec<String>,
}

#[derive(Clone, Debug, Serialize)]
pub struct LiveRunEndedPayload {
  pub ended_at: String,
  pub status: String,
  pub total_tests: i32,
  pub passed: i32,
  pub failed: i32,
  pub duration_ms: i64,
}

#[derive(Clone, Debug, Serialize)]
pub struct LiveTestStartedPayload {
  pub test_id: String,
  pub test_name: String,
  pub spec_name: String,
  pub started_at: String,
  pub status: String,
}

#[derive(Clone, Debug, Serialize)]
pub struct LiveTestEndedPayload {
  pub test_id: String,
  pub status: String,
  pub duration_ms: i64,
  pub error: Option<String>,
  pub ended_at: String,
}

#[derive(Clone, Debug, Serialize)]
pub struct LiveEntryRecordedPayload {
  pub id: i64,
  pub test_id: String,
  pub timestamp: String,
  pub system: String,
  pub action: String,
  pub result: String,
  pub input: Option<String>,
  pub output: Option<String>,
  pub metadata: Option<String>,
  pub expected: Option<String>,
  pub actual: Option<String>,
  pub error: Option<String>,
  pub trace_id: Option<String>,
}

#[derive(Clone, Debug, Serialize)]
pub struct LiveSpanRecordedPayload {
  pub id: i64,
  pub test_id: Option<String>,
  pub trace_id: String,
  pub span_id: String,
  pub parent_span_id: Option<String>,
  pub operation_name: String,
  pub service_name: String,
  pub start_time_nanos: i64,
  pub end_time_nanos: i64,
  pub status: String,
  pub attributes: Option<String>,
  pub exception_type: Option<String>,
  pub exception_message: Option<String>,
  pub exception_stack_trace: Option<String>,
}

#[derive(Clone, Debug, Serialize)]
pub struct LiveSnapshotPayload {
  pub id: i64,
  pub test_id: String,
  pub system: String,
  pub state_json: String,
  pub summary: String,
}

#[derive(Clone)]
pub struct EventIngestor {
  sender: mpsc::UnboundedSender<IngestCommand>,
}

impl EventIngestor {
  #[must_use]
  pub fn new(repository: Arc<Repository>) -> Self {
    Self::with_config(repository, DEFAULT_MAX_BATCH_SIZE, DEFAULT_MAX_BATCH_DELAY)
  }

  #[must_use]
  pub fn with_config(
    repository: Arc<Repository>,
    max_batch_size: usize,
    max_batch_delay: Duration,
  ) -> Self {
    let (sender, receiver) = mpsc::unbounded_channel();
    tokio::spawn(run_ingest_loop(
      repository,
      receiver,
      max_batch_size,
      max_batch_delay,
    ));
    Self { sender }
  }

  pub fn enqueue(&self, event: PersistedPortalEvent, flush_immediately: bool) -> Result<()> {
    self
      .sender
      .send(IngestCommand::Persist {
        event: Box::new(event),
        flush_immediately,
      })
      .map_err(|_| AppError::Startup("persistence worker is not running".to_string()))
  }

  pub async fn flush_pending(&self) -> Result<()> {
    let (reply_tx, reply_rx) = oneshot::channel();
    self
      .sender
      .send(IngestCommand::Flush { reply: reply_tx })
      .map_err(|_| AppError::Startup("persistence worker is not running".to_string()))?;
    reply_rx
      .await
      .map_err(|_| AppError::Startup("persistence worker stopped before flushing".to_string()))?
  }
}

enum IngestCommand {
  Persist {
    event: Box<PersistedPortalEvent>,
    flush_immediately: bool,
  },
  Flush {
    reply: oneshot::Sender<Result<()>>,
  },
}

async fn run_ingest_loop(
  repository: Arc<Repository>,
  mut receiver: mpsc::UnboundedReceiver<IngestCommand>,
  max_batch_size: usize,
  max_batch_delay: Duration,
) {
  let mut pending = Vec::with_capacity(max_batch_size.max(1));

  loop {
    if pending.is_empty() {
      let Some(command) = receiver.recv().await else {
        break;
      };
      handle_command(
        command,
        repository.as_ref(),
        &mut pending,
        max_batch_size.max(1),
      );
      continue;
    }

    let delay = tokio::time::sleep(max_batch_delay);
    tokio::pin!(delay);

    tokio::select! {
      maybe_command = receiver.recv() => {
        if let Some(command) = maybe_command {
          handle_command(
            command,
            repository.as_ref(),
            &mut pending,
            max_batch_size.max(1),
          );
        } else {
          if let Err(error) = persist_pending(repository.as_ref(), &mut pending) {
            warn!(%error, "Failed to flush pending portal events during shutdown");
          }
          break;
        }
      }
      () = &mut delay => {
        if let Err(error) = persist_pending(repository.as_ref(), &mut pending) {
          warn!(%error, "Failed to persist a batched portal event flush");
        }
      }
    }
  }
}

fn handle_command(
  command: IngestCommand,
  repository: &Repository,
  pending: &mut Vec<PersistedPortalEvent>,
  max_batch_size: usize,
) {
  match command {
    IngestCommand::Persist {
      event,
      flush_immediately,
    } => {
      pending.push(*event);
      if (flush_immediately || pending.len() >= max_batch_size)
        && let Err(error) = persist_pending(repository, pending)
      {
        warn!(%error, "Failed to persist portal events after batch threshold");
      }
    }
    IngestCommand::Flush { reply } => {
      let _ = reply.send(persist_pending(repository, pending));
    }
  }
}

fn persist_pending(repository: &Repository, pending: &mut Vec<PersistedPortalEvent>) -> Result<()> {
  if pending.is_empty() {
    return Ok(());
  }

  let batch = std::mem::take(pending);
  match repository.apply_persisted_events(&batch) {
    Ok(()) => Ok(()),
    Err(batch_error) => {
      warn!(
        %batch_error,
        batch_size = batch.len(),
        "Batch persistence failed, retrying events individually"
      );

      let mut first_individual_error = None;
      for event in &batch {
        if let Err(error) = repository.apply_persisted_events(std::slice::from_ref(event)) {
          warn!(%error, "Failed to persist portal event after individual retry");
          if first_individual_error.is_none() {
            first_individual_error = Some(error);
          }
        }
      }

      if let Some(error) = first_individual_error {
        Err(error)
      } else {
        Ok(())
      }
    }
  }
}

fn live_record_id(seq: u64) -> i64 {
  let bounded = seq.min(i64::MAX as u64);
  -bounded.cast_signed()
}
