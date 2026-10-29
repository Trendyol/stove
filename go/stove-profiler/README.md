# Stove profiler for Go

Native Go profiling for Stove 0.28.0 (upcoming), using Grafana `pyroscope-go` 1.4.3. Uploads go directly to the existing Stove Server; no separate profiling backend is required.

```go
import stoveprofiler "github.com/trendyol/stove/go/stove-profiler"

profiler, err := stoveprofiler.StartFromEnv()
if err != nil { return err }
defer profiler.Close()
```

When `STOVE_PROFILING_SERVER_URL` is absent, this returns nil and does nothing. When enabled, `STOVE_RUN_ID` and `STOVE_PROFILING_SERVICE_NAME` are required. Use dashboard-exposed configuration to supply the exact run identity to the process/container. Optional `STOVE_PROFILING_INTERVAL_SECONDS` defaults to 10, and `STOVE_PROFILING_TYPES` defaults to `cpu`.

CPU is always included for SDK final-flush support. Allocation, in-use heap, goroutine, mutex, and block profiles are available. The wrapper does not force GC or change runtime mutex/block sampling settings. Run only one profiler per process. Call `Close` during graceful shutdown; upload delivery is best effort and failures are logged.

`Start(Config)` supports explicit configuration. `StartFromEnv(func(*Config))` can supply custom headers, an HTTP client for CA trust/mTLS, and a logger. Identity headers cannot be overridden; redirects are refused. Uploads have a five-second deadline and up to three attempts for temporary errors.

See [the profiling guide](https://trendyol.github.io/stove/observability/profiling/#go-developers) and [the Go showcase](../../recipes/process/golang/go-showcase) for Kotlin environment mappings, HTTPS, authentication, shutdown, supported sample semantics, and Drill Down/MCP investigation. Before publication, use a local module replacement as in the showcase; after publication install `github.com/trendyol/stove/go/stove-profiler@v0.28.0`.
