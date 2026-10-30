# Profiling and Drill Down

Use this guide to configure continuous profiles or investigate CPU/allocation hotspots. Profiling is introduced in **0.28.0 (upcoming)**. Check the project's resolved libraries, tracing plugin, and server version before suggesting this API; do not upgrade a downstream project merely to match these examples.

## Establish what can be profiled

`stoveTracing.profiling` attaches Pyroscope **Java** agent 2.9.2 to selected Gradle `Test` JVMs. An in-process JVM application is included, along with test code and background work. A Go, Python, Node.js, Rust, separate JVM, container, or deployed application is not automatically profiled by attaching that agent to the harness.

Stove Server accepts Pyroscope Java JFR and the native Go SDK's delta pprof multipart uploads. Pyroscope Push RPC, arbitrary cumulative heap/lock pprof files, OTLP Profiles, and other runtimes are unsupported. A separate JVM still requires custom agent/lifecycle integration. Exact run identity can be exposed by `DashboardExternalProfilingOptions`; never substitute the latest run.

## Native Go application

Use `github.com/trendyol/stove/go/stove-profiler` (0.28.0 upcoming, Go >=1.26.2), which wraps `pyroscope-go` 1.4.3. Before publication use a matching checkout/local `replace`. In `main`, call `stoveprofiler.StartFromEnv()`, handle configuration errors, and defer its nil-safe, idempotent `Close()`. The wrapper is disabled when `STOVE_PROFILING_SERVER_URL` is absent. Gradle's Java profiling plugin is unnecessary for collecting Go profiles.

Add `externalProfiling = DashboardExternalProfilingOptions(serverUrl = url, profileTypes = listOf("cpu", "alloc_space", "inuse_space", "goroutines"))` to `DashboardSystemOptions`, with dashboard event ingestion configured for the same server. Defaults: service is dashboard `appName`, interval 10 seconds, types CPU only. Put dashboard before the AUT runner. Map its exposed configuration using the existing process/container `envMapper`:

| Configuration key | Environment variable |
|---|---|
| `stove.profiling.serverUrl` | `STOVE_PROFILING_SERVER_URL` |
| `stove.profiling.runId` | `STOVE_RUN_ID` |
| `stove.profiling.serviceName` | `STOVE_PROFILING_SERVICE_NAME` |
| `stove.profiling.uploadIntervalSeconds` | `STOVE_PROFILING_INTERVAL_SECONDS` |
| `stove.profiling.profileTypes` | `STOVE_PROFILING_TYPES` |

Run IDs and service names must be printable ASCII without surrounding whitespace, at most 256 bytes. Set an explicit profiling service name when the dashboard app name does not meet that contract. The wrapper generates an instance ID and protects `X-Stove-*` headers. Preserve exact run identity. URLs must be reachable from the application; container localhost is normally not the host. A provided/deployed app needs explicit lifecycle/identity integration. See the repository's `recipes/process/golang/go-showcase` for process and container wiring and `docs/observability/profiling.md#go-developers` for complete configuration.

Supported types are CPU, alloc_objects/alloc_space, inuse_objects/inuse_space, goroutines, mutex_count/mutex_duration, block_count/block_duration. CPU is always included because the SDK's final flush needs its collector. Do not run a second Pyroscope/CPU profiler in that process. `PYROSCOPE_ADHOC_SERVER_ADDRESS` must be unset. Interval is 1–3600 seconds. Allocation/lock values are deltas; heap/goroutine snapshots use the newest selected recording per service/instance/metric, including empty snapshots. Heap capture depends on natural GC; the wrapper disables forced GC. Block/mutex sampling requires app-selected runtime sampling settings, which the wrapper does not change.

Ensure graceful shutdown reaches `Close()` before potentially blocking dependency cleanup; register its defer last (Go runs defers in reverse order), or close explicitly after draining requests. os.Exit/log.Fatal/SIGKILL bypass defers. Allow enough runner shutdown time for queued uploads. Delivery is best effort, logged on failure, with up to three attempts per upload and a five-second total deadline. The server accepts final uploads to a completed retained run. Refresh after teardown. Do not claim no profiles means no activity or that in-use heap proves a leak.

Go can configure headers and an approved HTTP client through `StartFromEnv(func(c *stoveprofiler.Config) { ... })` or `Start(Config{...})` for gateway tokens, CA trust, or mTLS. Redirects are refused. JVM dashboard event ingestion still needs its own supported authentication path. The SDK sends `spyName=gospy`, epoch-nanosecond from/until, a gzip pprof `profile` part, and `sample_type_config`. Shared ingress must allow `/ingest`, identity headers, and 16 MiB uploads. REST/MCP/Drill Down reuse the same query model, with `go:*` metrics. OTel trace/span labels are not automatically attached to Go profiles; explicit runtime/pprof labels are required for label-based correlation.

## Configure the existing JVM test setup

Reuse the project's version catalog and plugin management. The plugin ID is `com.trendyol.stove.tracing`; do not add the agent to the application runtime classpath. The plugin resolves the agent itself.

```kotlin
stoveTracing {
    serviceName.set("checkout-api")
    testTaskNames.set(listOf("e2eTest")) // Match the existing Gradle Test task.
    profiling {
        enabled.set(true)
        serverUrl.set("http://localhost:4040")
        allocations.set(true) // Optional; omit for CPU-only collection.
    }
}
```

Keep `stove-dashboard` and register `dashboard { DashboardSystemOptions(appName = "checkout-api") }` in the existing Stove lifecycle. The dashboard starts collection after assigning its run ID and flushes at teardown. Pair startup with `Stove.stop()`; do not start a competing Pyroscope agent. The outer `stoveTracing.enabled` controls OTel wiring independently from `profiling.enabled`. A span receiver is not needed for profiling alone.

The bundled extension is extracted by `stoveExtractPyroscopeExtension`; downstream builds need no direct GitHub access or extra repository. It preserves other OTel extensions and sets `otel.pyroscope.start.profiling=false` and `otel.pyroscope.root.span.only=true`. Stove retains profiler lifecycle ownership. Do not manually start the extension’s fallback profiler or add Maven `io.pyroscope:otel` as a substitute. Set `profiling.traceCorrelation=false` to opt out. The supported agent/extension pair is 2.9.2/2.1.4; verify overrides.

Default upload interval: 10 seconds. Optional `uploadIntervalSeconds` accepts 1–3600. `allocationThreshold` defaults to `512k`; `liveAllocations` defaults to false and also enables allocation sampling; `lockThreshold` is disabled unless set (for example `"10ms"`). Start with the evidence requested; extra profile types add overhead. No forced GC occurs.

Use the configured Gradle test task. IntelliJ's native Run Test bypasses task agent wiring: delegate execution to Gradle or run the task from its Gradle tool window. Missing uploads are not necessarily a frequency problem. Short runs can appear after final flush; a hard process kill loses the pending interval.

## Shared server

`profiling.serverUrl` is an independent HTTP(S) **base URL**. It does not inherit `DashboardIngestion.Http`. Point both to the same logical server/storage:

```kotlin
// Gradle profiling block:
serverUrl.set("https://stove.example.internal")
```

```kotlin
// Stove dashboard options:
DashboardSystemOptions(
    appName = "checkout-api",
    ingestion = DashboardIngestion.Http("https://stove.example.internal")
)
```

The profiler appends `/ingest`; dashboard events use `/api/v1/events`. Both use HTTP port 4040 by default, usually exposed as HTTPS 443; profiles never use gRPC port 4041. Preserve gateway prefixes, query parameters, multipart boundaries/bodies, and `X-Stove-*` headers. These identifiers associate evidence; they are not authentication credentials. Every replica must see the same run database.

For `/observe`, set both client base URLs to include `/observe` and set server `public_url` consistently. The gateway may preserve the prefix or strip it once. `public_url` does not configure clients or TLS. One explicitly wired project environment variable can supply both URLs; do not claim `STOVE_SERVER_URL` is automatically recognized by Stove.

The test JVM must trust the HTTPS certificate and hostname. Provision the approved internal CA in that JVM's trust store; configuring only the Gradle daemon or trusting the site in a browser is insufficient. Keep trust-store secrets in the existing CI mechanism. Neither client DSL exposes arbitrary authentication headers or dedicated client-certificate setup. Browser SSO and MCP authentication do not authenticate uploads. Do not invent token properties, embed credentials in URLs, disable gateway authentication, or bypass TLS checks; machine authentication requires additional client integration or an approved machine access path.

All ingress hops must allow 16 MiB for the entire profile request including multipart overhead. This does not raise the server's decoded/normalized limits. The integration sets a 5-second profile export timeout and two ingestion retries; dashboard HTTP uses a 5-second connect / 10-second request timeout. A proxy timeout does not extend these client deadlines. Collection is best effort; inspect missing intervals rather than assuming durable delivery.

Verify from the actual runner: health route, Gradle test execution, dashboard run creation, successful `POST /ingest`, then the exact run's Drill Down/API results. Health or browser success alone does not prove Java TLS or ingestion. Distinguish authentication redirects/401/403, prefix errors/404/405, invalid multipart/400, upload-size failures/413 or 400, and missing-run/conversion/database failures/503. Use status bodies and server logs; do not log profile bodies or credentials. Full deployment examples and diagnostics: [Shared Stove Server over HTTPS](https://trendyol.github.io/stove/observability/profiling/#shared-stove-server-over-https).

## Verify and investigate

- Select the exact application/run and **Drill Down · whole run**. Check the actual service and profile metric, recording window, and graph coverage. Search methods/files, zoom frames, and inspect self versus inclusive cost.
- A selected test window includes whole overlapping recordings and other threads. Do not describe it as exact per-test profiling. Trace/span selectors require recording correlation labels. When JVM tracing and profiling are enabled, the plugin bundles Pyroscope OTel extension 2.1.4 and enables correlation by default (`traceCorrelation=true`). Use `pyroscope.profile.id` from a local root span as `span_id`, not an arbitrary child span ID. Without that attribute, search by trace only. Empty results are not proof of no work. Async/coroutine thread transitions are not guaranteed to retain sample context.
- Use `stove_profiles` with an exact `run_id` and returned service/metric names. See [mcp.md](mcp.md#profile-driven-investigation) for baseline restrictions. `stove_diagnose` profile evidence remains run-wide even for a test selector.
- Check `truncated`, omitted stack values, units, duration, sampling settings, process count, and workload. REST returns at most 32 series, 20 hotspots, and 200 stacks per series; the UI's sort reorders the returned hotspots. MCP omits stacks and further bounds summaries.
- Live values use the latest recording per instance, not the sum of live snapshots. Allocation pressure and surviving objects are not proof of a leak or measurements of retained heap. Corroborate suspicious growth with heap/GC evidence.
- Inspect source before claiming a root cause. Cite run/window, units, source profile IDs, and coverage. For an authorized fix, rerun a comparable scenario and compare evidence; neither absolute totals nor a high share alone establish a regression.

## Diagnose missing data

Check in this order as applicable: correct runtime/format; selected Gradle task and Java-agent attachment; normal dashboard startup; same server/storage for run events and profiles; upload/teardown logs; reachable `/ingest` route, TLS trust, headers and body size; platform support or a competing profiler. A 503 can mean the run-start event has not arrived or both conversion slots are busy. Persistent 503 needs diagnosis, not unbounded retries.

The server retains at most 256 chunks / 64 MiB compressed per run and decodes at most 64 MiB per query. Retention may remove older evidence. Two conversions can run concurrently; payload limits are not a hard memory cap for the parser. The embedded Go `jfr-parser` is part of the server binary, not a downstream service; the same archive also decodes Go pprof.

Full user guide and wire contract: [Profiling and Drill Down](https://trendyol.github.io/stove/observability/profiling/) or `docs/observability/profiling.md` in a matching Stove checkout. Source anchors: `StoveProfilingExtension`, `ProfilingTaskConfigurator`, `DashboardProfiling`, `FlushingProfileScheduler`, `server/stove-server/src/profiling/`.
