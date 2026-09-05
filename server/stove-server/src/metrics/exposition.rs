use super::operation::{BOUNDS, Operation, Snapshot};
use std::fmt::Write;

pub(super) fn scalar(output: &mut String, name: &str, kind: &str, help: &str, value: u64) {
  description(output, name, kind, help);
  writeln!(output, "{name} {value}").unwrap();
}
fn description(output: &mut String, name: &str, kind: &str, help: &str) {
  writeln!(output, "# HELP {name} {help}\n# TYPE {name} {kind}").unwrap();
}

pub(super) fn operations<'a>(
  output: &mut String,
  counters: &str,
  duration: &str,
  operations: impl IntoIterator<Item = (&'a str, &'a Operation)>,
) {
  let snapshots: Vec<_> = operations
    .into_iter()
    .map(|(name, operation)| (name, operation.snapshot()))
    .collect();
  for (index, (suffix, kind, help)) in [
    ("in_flight", "gauge", "Operations queued or running."),
    (
      "in_flight_bytes",
      "gauge",
      "Encoded ingestion bytes queued or running; zero for other operations.",
    ),
    (
      "rejected_total",
      "counter",
      "Rejected admission attempts; zero for database operations.",
    ),
    (
      "completed_total",
      "counter",
      "Completed operations including failures.",
    ),
    ("failed_total", "counter", "Failed admitted operations."),
  ]
  .into_iter()
  .enumerate()
  {
    let metric = format!("{counters}_{suffix}");
    description(output, &metric, kind, help);
    for (name, snapshot) in &snapshots {
      writeln!(
        output,
        "{metric}{{operation=\"{name}\"}} {}",
        snapshot.counters[index]
      )
      .unwrap();
    }
  }
  description(
    output,
    duration,
    "histogram",
    "Operation duration in seconds; database timings exclude async scheduler wait.",
  );
  for (name, snapshot) in snapshots {
    histogram(output, duration, name, &snapshot);
  }
}

fn histogram(output: &mut String, metric: &str, operation: &str, snapshot: &Snapshot) {
  for (bound, count) in BOUNDS.iter().zip(snapshot.buckets) {
    writeln!(
      output,
      "{metric}_bucket{{operation=\"{operation}\",le=\"{}\"}} {count}",
      seconds(*bound)
    )
    .unwrap();
  }
  let count = snapshot.counters[3];
  writeln!(
    output,
    "{metric}_bucket{{operation=\"{operation}\",le=\"+Inf\"}} {count}"
  )
  .unwrap();
  writeln!(
    output,
    "{metric}_count{{operation=\"{operation}\"}} {count}"
  )
  .unwrap();
  writeln!(
    output,
    "{metric}_sum{{operation=\"{operation}\"}} {}",
    seconds(snapshot.micros)
  )
  .unwrap();
}
#[allow(clippy::cast_precision_loss)]
fn seconds(micros: u64) -> f64 {
  micros as f64 / 1_000_000.0
}
