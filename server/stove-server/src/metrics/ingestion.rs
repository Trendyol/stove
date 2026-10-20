use super::{ActiveGuard, AppMetrics, counter_vec, histogram, register};
use prometheus::{Gauge, Histogram, IntCounterVec, IntGauge, Registry};
use std::{
  collections::BTreeMap,
  sync::{Arc, Mutex},
  time::Instant,
};

pub(super) struct IngestionMetrics {
  pending: Mutex<Pending>,
  oldest: Gauge,
  active: IntGauge,
  duration: Histogram,
  events: IntCounterVec,
}
#[derive(Default)]
struct Pending {
  next: u64,
  events: BTreeMap<u64, Instant>,
}
impl IngestionMetrics {
  pub fn new(registry: &Registry) -> Self {
    let events = counter_vec(
      registry,
      "stove_app_ingestion_events_total",
      "Ingestion attempts including validation and persistence, by outcome.",
      &["outcome"],
    );
    for outcome in ["accepted", "duplicate", "error"] {
      events.with_label_values(&[outcome]);
    }
    let duration = histogram(
      registry,
      "stove_app_ingestion_duration_seconds",
      "Ingestion duration from preparation through commit, including database waits.",
      &[],
    )
    .with_label_values::<&str>(&[]);
    Self {
      pending: Mutex::new(Pending::default()),
      oldest: register(
        registry,
        Gauge::new(
          "stove_app_ingestion_oldest_pending_age_seconds",
          "Age of the oldest unfinished ingestion attempt; includes database waits, zero when idle.",
        ).expect("constant gauge"),
      ),
      active: register(
        registry,
        IntGauge::new(
          "stove_app_ingestion_in_flight",
          "Events currently being prepared and committed, including database waits.",
        ).expect("constant gauge"),
      ),
      duration,
      events,
    }
  }
  pub fn refresh(&self) {
    let pending = self
      .pending
      .lock()
      .unwrap_or_else(std::sync::PoisonError::into_inner);
    self.oldest.set(
      pending
        .events
        .first_key_value()
        .map_or(0., |(_, at)| at.elapsed().as_secs_f64()),
    );
  }
}
impl AppMetrics {
  pub fn ingest(self: &Arc<Self>) -> IngestionGuard {
    let mut pending = self
      .ingestion
      .pending
      .lock()
      .unwrap_or_else(std::sync::PoisonError::into_inner);
    let id = pending.next;
    pending.next += 1;
    pending.events.insert(id, Instant::now());
    IngestionGuard {
      metrics: self.clone(),
      id,
      outcome: "error",
      _active: ActiveGuard::new(self.ingestion.active.clone()),
    }
  }
}
pub(crate) struct IngestionGuard {
  metrics: Arc<AppMetrics>,
  id: u64,
  outcome: &'static str,
  _active: ActiveGuard,
}
impl IngestionGuard {
  pub fn finish(mut self, duplicate: bool) {
    self.outcome = if duplicate { "duplicate" } else { "accepted" };
  }
}
impl Drop for IngestionGuard {
  fn drop(&mut self) {
    let metrics = &self.metrics.ingestion;
    let started = metrics
      .pending
      .lock()
      .unwrap_or_else(std::sync::PoisonError::into_inner)
      .events
      .remove(&self.id);
    if let Some(started) = started {
      metrics.duration.observe(started.elapsed().as_secs_f64());
    }
    metrics.events.with_label_values(&[self.outcome]).inc();
  }
}
