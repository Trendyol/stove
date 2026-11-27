import type {
  ProfileReport,
  ProfileSeries,
  ProfileTrendReport,
  ProfileTrendSeries,
  Run,
  Span,
} from "../api/types";
import { frameKey, type ProfileFrame } from "../utils/profiles";

const frame = (name: string, file: string, line: number): ProfileFrame => ({ name, file, line });
const thread = frame("java.lang.Thread.run", "Thread.java", 840);
const controller = frame("demo.checkout.OrderController.create", "OrderController.kt", 42);
const order = frame("demo.checkout.OrderService.placeOrder", "OrderService.kt", 68);
const serialize = frame(
  "demo.checkout.OrderEventSerializer.serialize",
  "OrderEventSerializer.kt",
  31,
);
const json = frame(
  "com.fasterxml.jackson.core.json.UTF8JsonGenerator.writeString",
  "UTF8JsonGenerator.java",
  512,
);
const pricing = frame("demo.checkout.PriceCalculator.calculate", "PriceCalculator.kt", 27);
const decimal = frame("java.math.BigDecimal.multiply", "BigDecimal.java", 1598);
const inventory = frame("demo.checkout.InventoryRepository.mapRows", "InventoryRepository.kt", 54);
const copy = frame("java.util.Arrays.copyOf", "Arrays.java", 3537);
const worker = frame("demo.notifications.OrderCreatedHandler.handle", "OrderCreatedHandler.kt", 23);
const render = frame("demo.notifications.TemplateRenderer.render", "TemplateRenderer.kt", 47);
const maintenance = frame("demo.checkout.OrderCache.evictExpired", "OrderCache.kt", 89);
const metrics = [
  { metric: "process_cpu:cpu", unit: "nanoseconds", scale: 10_000_000 },
  { metric: "memory:alloc_in_new_tlab_bytes", unit: "bytes", scale: 65_536 },
];

/** Synthetic recordings derived from current local-root spans. This demo-only
 * projection shares the evidence lifecycle (replay, SQL deletion, purge, reset).
 * It does not simulate a profiler or contact an ingestion server. */
export function demoProfileReport(run: Run, spans: Span[], params: URLSearchParams): ProfileReport {
  const allowed = new Set([
    "service",
    "instance",
    "metric",
    "from_ms",
    "until_ms",
    "trace_id",
    "span_id",
  ]);
  for (const key of params.keys()) {
    if (!allowed.has(key)) throw new Error(`Unknown profile parameter: ${key}`);
  }
  const timestamp = (key: string) => {
    const raw = params.get(key);
    if (raw === null) return undefined;
    const value = Number(raw);
    if (!/^-?\d+$/.test(raw) || !Number.isSafeInteger(value)) throw new Error(`Invalid ${key}`);
    return value;
  };
  const from = timestamp("from_ms");
  const until = timestamp("until_ms");
  if (from !== undefined && until !== undefined && from >= until)
    throw new Error("from_ms must precede until_ms");
  const matches = (key: string, value: string) => !params.has(key) || params.get(key) === value;
  const groups = new Map<string, ProfileSeries>();
  const priceWeight = 2 + (Number(run.metadata.pipeline) % 3 || 0);
  for (const span of spans) {
    if (
      span.run_id !== run.id ||
      !matches("service", span.service_name) ||
      !matches("instance", "demo-jvm")
    )
      continue;
    const profileId = JSON.parse(span.attributes ?? "{}")["pyroscope.profile.id"];
    if (typeof profileId !== "string") continue;
    // Time queries select whole 3-second recordings, never individual samples.
    const start = Date.parse(run.started_at);
    const fromMs = start + Math.floor((span.start_time_nanos / 1e6 - start) / 3000) * 3000;
    const untilMs = fromMs + 3000;
    if ((from !== undefined && untilMs <= from) || (until !== undefined && fromMs >= until))
      continue;
    const isWorker = span.service_name === "notification-worker";
    const correlated = matches("trace_id", span.trace_id) && matches("span_id", profileId);
    const includeBackground = !params.has("trace_id") && !params.has("span_id");
    for (const { metric, unit, scale } of metrics) {
      if (!matches("metric", metric)) continue;
      const allocation = unit === "bytes";
      const paths = isWorker
        ? [
            { frames: [thread, worker, render, ...(allocation ? [copy] : [])], weight: 5 },
            { frames: [thread, worker, serialize, json], weight: 2 },
          ]
        : [
            { frames: [thread, controller, order, serialize, json], weight: allocation ? 12 : 5 },
            {
              frames: [
                thread,
                controller,
                order,
                pricing,
                ...(allocation ? [decimal, copy] : [decimal]),
              ],
              weight: priceWeight,
            },
            {
              frames: [thread, controller, order, inventory, ...(allocation ? [copy] : [])],
              weight: 2,
            },
          ];
      const samples = correlated ? [...paths] : [];
      if (includeBackground)
        samples.push({
          frames: [thread, ...(isWorker ? [render] : [maintenance]), ...(allocation ? [copy] : [])],
          weight: 1,
        });
      if (!samples.length) continue;
      const key = `${span.service_name}:${metric}`;
      let series = groups.get(key);
      if (!series) {
        series = {
          service: span.service_name,
          metric,
          unit,
          total: 0,
          sample_records: 0,
          from_ms: fromMs,
          until_ms: untilMs,
          sample_rates: [100],
          profile_ids: [],
          hotspots: [],
          stacks: [],
          omitted_stack_value: 0,
          findings: [],
        };
        groups.set(key, series);
      }
      series.from_ms = Math.min(series.from_ms, fromMs);
      series.until_ms = Math.max(series.until_ms, untilMs);
      series.profile_ids.push(`demo:${run.id}:${span.service_name}:${profileId}`);
      for (const { frames, weight } of samples) {
        const value = weight * scale;
        series.total += value;
        series.sample_records += weight;
        const existing = series.stacks.find(
          (s) => JSON.stringify(s.frames) === JSON.stringify(frames),
        );
        if (existing) existing.value += value;
        else series.stacks.push({ frames, value });
      }
    }
  }
  for (const series of groups.values()) {
    const methods = new Map<string, ProfileSeries["hotspots"][number]>();
    for (const stack of series.stacks) {
      const seen = new Set<string>();
      for (const [index, current] of stack.frames.entries()) {
        const key = frameKey(current);
        let hotspot = methods.get(key);
        if (!hotspot) {
          hotspot = { frame: current, self_value: 0, inclusive_value: 0, self_percent: 0 };
          methods.set(key, hotspot);
        }
        if (!seen.has(key)) hotspot.inclusive_value += stack.value;
        seen.add(key);
        if (index === stack.frames.length - 1) hotspot.self_value += stack.value;
      }
    }
    series.hotspots = [...methods.values()]
      .map((h) => ({ ...h, self_percent: (h.self_value / series.total) * 100 }))
      .sort((a, b) => b.self_value - a.self_value || a.frame.name.localeCompare(b.frame.name));
    series.stacks.sort((a, b) => b.value - a.value);
    const hottest = series.hotspots[0];
    series.findings.push({
      kind: "hotspot",
      evidence: `Fictional example: ${hottest.frame.name} accounts for ${hottest.self_percent.toFixed(1)}% of self ${series.unit}.`,
      suggestion:
        "Explore its callers and compare CPU with allocations. These synthetic samples do not explain the payment timeout; inspect the trace and mock evidence for that failure.",
    });
    if (series.sample_records < 20)
      series.findings.push({
        kind: "limited_coverage",
        evidence: `Only ${series.sample_records} fictional contributing sample records in this selection.`,
        suggestion:
          "Real investigations need a representative workload before drawing performance conclusions.",
      });
  }
  return {
    run_id: run.id,
    app_name: run.app_name,
    series: [...groups.values()],
    truncated: false,
    interpretation:
      "Fictional CPU and allocation recordings for exploring Drill Down, not measured performance. Time filters select whole overlapping recording windows, including background work. Trace and local-root span filters select tagged samples only. CPU time does not measure network waiting, and allocated bytes do not prove a memory leak. Real runs require optional profiling and correlation instrumentation.",
  };
}

export function demoProfileTrends(
  app: string,
  runs: Run[],
  spans: Span[],
  params: URLSearchParams,
): ProfileTrendReport {
  const latest = runs.slice(0, 10);
  const selectors = new URLSearchParams();
  for (const key of ["service", "metric"]) {
    const value = params.get(key);
    if (value !== null) selectors.set(key, value);
  }
  const groups = new Map<string, { run: Run; profile: ProfileSeries }[]>();
  for (const run of [...latest].reverse()) {
    for (const profile of demoProfileReport(run, spans, selectors).series) {
      if (profile.sample_records < 20) continue;
      const key = JSON.stringify([profile.service, profile.metric, profile.unit]);
      const group = groups.get(key) ?? [];
      group.push({ run, profile });
      groups.set(key, group);
    }
  }
  const series: ProfileTrendSeries[] = [...groups.values()].map((group) => {
    const { service, metric, unit } = group[0].profile;
    const candidates = new Map<string, ProfileFrame>();
    for (const { profile } of group) {
      for (const hotspot of profile.hotspots)
        if (hotspot.self_percent >= 20) candidates.set(frameKey(hotspot.frame), hotspot.frame);
    }
    const bottlenecks = [...candidates].flatMap(([key, frame]) => {
      const shares = group.map(
        ({ profile }) => profile.hotspots.find((h) => frameKey(h.frame) === key)?.self_percent ?? 0,
      );
      const hotRuns = shares.filter((share) => share >= 20).length;
      if (hotRuns < Math.max(2, Math.ceil(group.length / 2))) return [];
      const sorted = [...shares].sort((a, b) => a - b);
      return [
        {
          method: frame.name,
          file: frame.file,
          hot_runs: hotRuns,
          median_self_percent:
            (sorted[Math.floor(sorted.length / 2)] + sorted[Math.floor((sorted.length - 1) / 2)]) /
            2,
          change_percentage_points: shares[shares.length - 1] - shares[0],
          self_percent_by_run: shares,
        },
      ];
    });
    return {
      service,
      metric,
      unit,
      runs: group.map(({ run, profile }) => ({
        run_id: run.id,
        started_at: run.started_at,
        total: profile.total,
        sample_records: profile.sample_records,
      })),
      bottlenecks: bottlenecks.sort(
        (a, b) => b.hot_runs - a.hot_runs || b.median_self_percent - a.median_self_percent,
      ),
    };
  });
  return {
    app_name: app,
    passed_runs_considered: latest.length,
    run_limit: 10,
    hotspot_threshold_percent: 20,
    min_sample_records: 20,
    series: series.sort(
      (a, b) =>
        Number(b.bottlenecks.length > 0) - Number(a.bottlenecks.length > 0) ||
        b.runs.length - a.runs.length,
    ),
    truncated: false,
    interpretation: "Fictional profile history for exploring Drill Down.",
  };
}
