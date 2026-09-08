# Diagnosis with timeline and trace context — 8 September 2026

`stove_diagnose` now returns a bounded chronological failure timeline and a
relevant trace path alongside its ranked findings. This supplies execution
context in the first call, with exact follow-up calls for deeper inspection.

Each test includes at most five report entries and eight spans, across all
budgets. The trace retains its target and nearest ancestors. Error evidence now
outranks span count, so a large healthy trace cannot displace a smaller failed
trace. Stack excerpts stay in findings; the path adds span relationships,
services, operations, timing and citations. Counts identify omitted entries,
spans and traces. An uncorrelated trace is reported explicitly.

The benchmark reuses the same 888 ingested protobuf events and five synthetic
failures as the [findings-only benchmark](mcp-diagnose-2026-09-07.md).

| Compact diagnosis | Previous text tokens | With context | Local p95 |
|---|---:|---:|---:|
| Assertion mismatch | 778 | 1,152 | 0.88 ms |
| Database exception | 946 | 2,173 | 1.00 ms |
| Mock mismatch | 874 | 1,241 | 0.93 ms |
| Long-trace timeout | 968 | 2,230 | 2.75 ms |
| Wide catalog | 1,053 | 1,430 | 4.81 ms |
| All five, exact run ID | 3,931 | 7,536 | 9.34 ms |
| All five, app + CI metadata | 3,931 | 7,536 | 9.06 ms |

The added evidence costs 367–1,262 text tokens per test in these fixtures.
All 15 scenario/budget combinations preserve every seeded cause, the failed
report entry and the failed trace target when tracing exists. Checks also enforce
context limits and omission counts. The metadata-selected loop retrieves five
tests exactly once in three calls and terminates using returned continuations.
Nine protocol/tool error probes pass.

Eight-client bursts of 240 requests measured p95 16.79 ms for ordinary diagnoses
and 15.76 ms when including the wide-payload case. Sequential latency remained
in the same local range as the previous benchmark; differences between runs do
not establish a speed improvement. Responses are larger because they contain
additional evidence.

Method: Apple M1 Pro, macOS 26.6.2, release build, temporary file-backed SQLite,
persistent loopback HTTP/1.1, three warmups and 60 timed requests per case, 17
cases. RTT ends after the full body is read and excludes client JSON parsing and
tokenization. Text tokens use `o200k_base`; wire size is recorded separately.
These checks measure synthetic evidence preservation and traversal, not an LLM's
diagnosis accuracy, correct fixes, remote networking or production capacity.

Validation: 123 tests passed (95 library, 7 diagnosis endpoint, 14 existing MCP,
6 focused evidence, 1 real-server acceptance), plus strict Clippy across all
targets, formatting and diff checks. The added endpoint regression checks
out-of-order ingestion, chronological context, long paths, competing healthy
traces, test/run isolation, public citations and executable follow-up calls.
PostgreSQL load and actual model evaluation were not run.

Reproduce after the [benchmark setup](../acceptance-tests/README.md#mcp-failure-evidence-benchmark):

```shell
cargo build --release --locked --bin stove
target/mcp-benchmark-venv/bin/python scripts/benchmark-mcp.py \
  --suite diagnosis --output target/mcp-benchmark/diagnosis-context
```

[Recorded measurements](mcp-diagnose-context-2026-09-08.json) include binary
identity, environment, token counts, latency summaries, evidence checks and loop
results. Raw timings and response examples are generated in the output directory.
