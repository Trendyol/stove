# Production MCP baseline — 8 September 2026

This measures the existing deployment before the MCP changes in this branch.
The server reports version `1.0.0.561-SNAPSHOT`, advertises nine tools and does
not expose `stove_diagnose`. No deployment, ingestion or server mutation was
performed. The target was the newest of three recorded runs containing the same
failed cancellation test; all subsequent requests used that exact run/test pair.

## Latency and response size

| Request | Text tokens | Client p50 | Client p95 |
|---|---:|---:|---:|
| Failure detail, tiny | 2,758 | 102.47 ms | 133.83 ms |
| Failure detail, compact | 3,216 | 103.67 ms | 125.71 ms |
| Failure detail, full | 3,216 | 103.45 ms | 129.25 ms |
| Timeline, compact | 3,835 | 101.45 ms | 144.16 ms |
| Trace, critical path | 708 | 104.82 ms | 149.81 ms |
| Trace, tree | 707 | 79.03 ms | 112.22 ms |
| Trace, exceptions | 707 | 79.21 ms | 96.28 ms |
| Snapshots, compact | 8,682 | 120.03 ms | 155.22 ms |
| Mock interactions, compact | 1,684 | 66.17 ms | 98.81 ms |

Method: one persistent HTTPS connection through the internal gateway, no
concurrency, 150 ms pause after every request, two warmups and 20 timed requests
per case (180 measured requests). Latency includes the full response body and
excludes client JSON parsing/tokenization. Percentiles use nearest rank. Token
counts use `o200k_base` on the actual text fallback, including its heading and
pretty-printed JSON. Each case returned a stable body across its timed samples.
Discovery, exploratory calls and three error probes were separate from these
distributions. An initial measurement attempt was restarted after adapting the
harness to the old deployment's heading-prefixed text format.

These are small-sample observations for one recorded failure, not a load test or
server-only timings. Different cases were measured sequentially, so their latency
differences can reflect network conditions. They cannot be compared directly
with the local SQLite benchmark to claim an improvement from the new code.

## Evidence usefulness

One existing failure-detail call already identifies the failed state assertion:
expected `CANCELLED`, received `APPROVE_WAITING`. It includes the create, cancel
and subsequent read sequence. The cancel step passed its recorded assertion;
the read then returned the old state. This is enough to locate the behavioral
failure without fetching every evidence tool.

The Kafka snapshot additionally records a calendar-delete event. That rules out
describing the observed execution as having performed no cancellation-related
work, but does not explain why the returned state remained unchanged. All 32
correlated spans are reported without errors or exceptions. The evidence alone
does not establish an exact defective source line or a correct fix.

## Confirmed behavior gaps

- All three trace views return the same single span in `critical_path`, with
  no tree output. The exceptions view also includes that non-error span.
- Each trace response reports 32 total spans and zero omitted spans despite
  returning only one unique span.
- An unknown argument and an invalid budget silently succeed. A missing test
  selector does produce an error, delivered as JSON-RPC `-32001`.
- Formatting adds avoidable tokens: the compact detail's existing structured
  content serializes to 2,387 compact JSON tokens versus 3,216 text tokens with
  the current heading/indentation, a 26% reduction without selecting less data.
- The compact snapshot response is 8,682 tokens / 56,186 wire bytes for this
  single test. Agents benefit from selecting evidence rather than automatically
  fetching every snapshot.

The branch already addresses trace-view selection, omitted-span accounting,
argument validation, recoverable tool errors and compact text serialization.
Its new diagnosis tool also targets fewer evidence-discovery calls. This baseline
supports those correctness and agent-efficiency changes; it does not establish
that the old server was too slow or that diagnosis improves correct-fix rates.

Repeat the same exact selection after deployment to compare end-to-end latency,
response size, evidence preservation and required calls. No after-deployment
measurement is included here.

[Aggregate measurements](mcp-production-baseline-2026-09-08.json) preserve timings,
sizes, version and behavioral checks. Internal endpoint, selectors and captured
payloads are excluded from that artifact. Raw responses and exact selectors are
stored locally under the ignored `target/mcp-benchmark/production-baseline/`.
