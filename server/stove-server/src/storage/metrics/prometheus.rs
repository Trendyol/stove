//! Metric types, synchronization, histogram buckets and exposition are owned by
//! the prometheus crate. Each repository has an isolated registry (no globals).
use super::{DatabaseMetrics, Outcome};
mod gauges;
use crate::metrics::{AppMetrics, counter_vec, gauge_vec, int_gauge_vec, register};
use gauges::{GAUGES, age};
use prometheus::{
  Encoder, GaugeVec, HistogramOpts, HistogramVec, IntCounter, IntCounterVec, IntGaugeVec, Registry,
  TextEncoder,
};
use std::collections::{BTreeMap, HashMap};
use std::sync::Arc;

pub(super) struct Instruments {
  registry: Registry,
  pub app: Arc<AppMetrics>,
  pub durations: HistogramVec,
  pub errors: IntCounterVec,
  timeouts: IntCounterVec,
  busy_errors: IntCounterVec,
  last_success: GaugeVec,
  capacity: BTreeMap<&'static str, IntGaugeVec>,
  counters: BTreeMap<&'static str, IntCounter>,
  gauges: BTreeMap<&'static str, GaugeVec>,
  deadlocks: IntCounterVec,
  health: IntGaugeVec,
  health_age: GaugeVec,
  health_duration: GaugeVec,
}
impl Instruments {
  pub fn new(instance: &str, backend: &str) -> Self {
    let registry = Registry::new_custom(
      None,
      Some(HashMap::from([
        ("stove_instance".into(), instance.into()),
        ("backend".into(), backend.into()),
      ])),
    )
    .expect("constant registry labels");
    let app = AppMetrics::new(&registry);
    let labels = &["kind", "operation", "role"];
    let durations = register(
      &registry,
      HistogramVec::new(
        HistogramOpts::new(
          "stove_db_duration_seconds",
          "Storage operation, execution, acquisition and maintenance duration.",
        )
        .buckets(vec![
          0.0001, 0.0005, 0.001, 0.0025, 0.005, 0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1., 2., 5., 10.,
          30., 60.,
        ]),
        labels,
      )
      .expect("constant histogram labels"),
    );
    let errors = counter_vec(
      &registry,
      "stove_db_errors_total",
      "Failed storage operations.",
      labels,
    );
    let timeouts = counter_vec(
      &registry,
      "stove_db_timeouts_total",
      "Connection acquisition and identifiable query timeouts.",
      labels,
    );
    let busy_errors = counter_vec(
      &registry,
      "stove_db_busy_errors_total",
      "SQLite busy or locked errors.",
      labels,
    );
    let last_success = gauge_vec(
      &registry,
      "stove_db_last_success_timestamp_seconds",
      "Last successful operation or committed retention timestamp.",
      labels,
    );
    let capacity = capacity_metrics(&registry);
    let counters = event_counters(&registry);
    let gauges = diagnostic_gauges(&registry);
    let deadlocks = counter_vec(
      &registry,
      "stove_db_postgres_deadlocks_total",
      "Database-wide deadlocks since PostgreSQL statistics reset.",
      &[],
    );
    let health = int_gauge_vec(
      &registry,
      "stove_db_health",
      "Connection role availability probe result.",
      &["role"],
    );
    let health_age = gauge_vec(
      &registry,
      "stove_db_health_age_seconds",
      "Age of the last connection role probe.",
      &["role"],
    );
    let health_duration = gauge_vec(
      &registry,
      "stove_db_health_duration_seconds",
      "Duration of the last connection role probe.",
      &["role"],
    );
    Self {
      registry,
      app,
      durations,
      errors,
      timeouts,
      busy_errors,
      last_success,
      capacity,
      counters,
      gauges,
      deadlocks,
      health,
      health_age,
      health_duration,
    }
  }
  pub fn observe(&self, kind: &str, name: &str, role: &str, seconds: f64, outcome: Outcome) {
    let labels = &[kind, name, role];
    self.durations.with_label_values(labels).observe(seconds);
    self
      .errors
      .with_label_values(labels)
      .inc_by(u64::from(outcome.failed));
    self
      .timeouts
      .with_label_values(labels)
      .inc_by(u64::from(outcome.timeout));
    self
      .busy_errors
      .with_label_values(labels)
      .inc_by(u64::from(outcome.busy));
    if !outcome.failed {
      self
        .last_success
        .with_label_values(labels)
        .set(chrono::Utc::now().timestamp_millis() as f64 / 1000.);
    }
  }
  pub fn increment(&self, name: &'static str, amount: u64) {
    self.counters[name].inc_by(amount);
  }
  pub fn counters(&self) -> BTreeMap<String, u64> {
    self
      .counters
      .iter()
      .map(|(name, metric)| ((*name).into(), metric.get()))
      .collect()
  }
  pub fn waiter_started(&self, role: &str) {
    self.capacity["waiters"].with_label_values(&[role]).inc();
  }
  pub fn waiter_finished(&self, role: &str) {
    self.capacity["waiters"].with_label_values(&[role]).dec();
  }
  pub fn waiters(&self, role: &str) -> u64 {
    u64::try_from(self.capacity["waiters"].with_label_values(&[role]).get()).unwrap_or(0)
  }
  pub fn encode(&self, snapshot: &DatabaseMetrics) -> String {
    self.refresh_gauges(snapshot);
    self.app.refresh();
    // Mirror PostgreSQL's external counter, including statistics resets. All
    // refresh/gather calls are serialized by StorageMetrics::exposition.
    if let Some(value) = snapshot
      .diagnostics
      .deadlocks
      .and_then(|value| u64::try_from(value).ok())
    {
      let counter = self.deadlocks.with_label_values::<&str>(&[]);
      if value < counter.get() {
        counter.reset();
      }
      counter.inc_by(value - counter.get());
    } else {
      let _ = self.deadlocks.remove_label_values::<&str>(&[]);
    }
    let families = self.registry.gather();
    let mut bytes = Vec::new();
    TextEncoder::new()
      .encode(&families, &mut bytes)
      .expect("valid registered metrics");
    String::from_utf8(bytes).expect("Prometheus text is UTF-8")
  }
  fn set_gauge(&self, name: &str, value: Option<f64>) {
    let gauge = &self.gauges[name];
    if let Some(value) = value {
      gauge.with_label_values::<&str>(&[]).set(value);
    } else {
      let _ = gauge.remove_label_values::<&str>(&[]);
    }
  }
  fn refresh_gauges(&self, snapshot: &DatabaseMetrics) {
    for lane in &snapshot.capacity {
      for (name, value) in [
        ("maximum", lane.maximum),
        ("connections", lane.connections),
        ("idle", lane.idle),
        ("busy", lane.busy),
      ] {
        self.capacity[name]
          .with_label_values(&[&lane.role])
          .set(i64::from(value));
      }
    }
    for check in &snapshot.health {
      self
        .health
        .with_label_values(&[&check.role])
        .set(i64::from(check.healthy));
      self
        .health_duration
        .with_label_values(&[&check.role])
        .set(check.duration_ms / 1000.);
      self
        .health_age
        .with_label_values(&[&check.role])
        .set(age(check.checked_at));
    }
    for &(name, value) in GAUGES {
      self.set_gauge(name, value(snapshot));
    }
  }
}
fn capacity_metrics(registry: &Registry) -> BTreeMap<&'static str, IntGaugeVec> {
  ["maximum", "connections", "idle", "busy", "waiters"]
    .into_iter()
    .map(|name| {
      (
        name,
        int_gauge_vec(
          registry,
          &format!("stove_db_capacity_{name}"),
          "Database capacity by connection role.",
          &["role"],
        ),
      )
    })
    .collect()
}

fn event_counters(registry: &Registry) -> BTreeMap<&'static str, IntCounter> {
  [
    "events_committed",
    "events_duplicate",
    "retention_runs_deleted",
    "purge_runs_deleted",
  ]
  .into_iter()
  .map(|name| {
    (
      name,
      register(
        registry,
        IntCounter::new(
          format!("stove_db_{name}_total"),
          "Committed storage events.",
        )
        .expect("constant counter name"),
      ),
    )
  })
  .collect()
}

fn diagnostic_gauges(registry: &Registry) -> BTreeMap<&'static str, GaugeVec> {
  GAUGES
    .iter()
    .map(|&(name, _)| {
      (
        name,
        gauge_vec(
          registry,
          &format!("stove_db_{name}"),
          "Latest sampled database diagnostic.",
          &[],
        ),
      )
    })
    .collect()
}
