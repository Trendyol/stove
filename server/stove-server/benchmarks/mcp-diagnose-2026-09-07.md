# CI diagnosis and agent-loop benchmark — 7 September 2026

`stove_diagnose` retrieves useful findings in the first call and returns exact
continuations for agents investigating more failures. It resolves a CI execution
by run ID or an exact app/metadata selector, gathers evidence internally, ranks
recorded observations, and supplies source citations and evidence calls.

The same five synthetic failures used by the [earlier benchmark](mcp-2026-09-07.md)
were ingested through the public protobuf HTTP endpoint: an assertion mismatch,
duplicate-key exception, mock mismatch, long-trace timeout and wide catalog error.
All **15 scenario/budget combinations** retained the exact seeded cause. The wide
case required **no supplied JSON pointer**: the server extracted its diagnostic
field before clipping the payload.

| Compact diagnosis | Text tokens | Local p95 |
|---|---:|---:|
| Assertion mismatch | 778 | 1.02 ms |
| Database exception | 946 | 1.43 ms |
| Mock mismatch | 874 | 1.29 ms |
| Long-trace timeout | 968 | 3.05 ms |
| Wide catalog | 1,053 | 5.62 ms |
| All five failures, exact run ID | 3,931 | 9.94 ms |
| All five failures, app + CI metadata | 3,931 | 9.76 ms |

The whole-run calls use `limit=5`. The default is three failures per page, with a
maximum of five; larger runs require continuation. Individual findings are capped
at eight per test. Repeated mock near misses are grouped with occurrence counts;
the fixture's 25 identical mismatches occupy one finding.

The agent-loop check starts with app/metadata and `limit=2`, then follows only the
returned `next_tool_call`. It retrieved **all five tests exactly once in three
calls**, with every cause present, and stopped when the continuation became null.
An endpoint regression test also inserts a newer run between pages and verifies
that continuation remains on the original run. Raw-evidence follow-up calls from
exception and assertion findings were executed successfully in endpoint tests.

Eight-client, 240-request bursts measured p95 **18.36 ms** for ordinary diagnoses
and **16.71 ms** including the wide fixture. These are short local client-observed
workloads, not sustained capacity or a production latency promise.

Method: Apple M1 Pro, macOS 26.6.2, release build, temporary file-backed SQLite,
888 events, persistent loopback HTTP/1.1. Each of 17 cases has three warmups and
60 timed calls (1,020 sequential measurements). RTT ends after reading the full
body and excludes client JSON parsing and tokenization. Tokens use
`tiktoken 0.14.0` / `o200k_base`, counting the text fallback; the full wire envelope
is recorded separately. The paged loop is a correctness check, not a repeated
latency distribution. No model API was called.

These packets deliberately select evidence. Lower token counts than a full detail
response do not mean equal-information compression. Ranked exceptions, mock
mismatches and diagnostic fields can be symptoms or unrelated observations within
a failed test; they are not proof of root cause. The benchmark checks marker
presence, scope and traversal, not an LLM's reasoning or a proposed fix.

The scanner recognizes explicit diagnostic fields and nested error messages, skips
sensitive subtrees, visits up to 50,000 JSON nodes per test and skips payloads over
2 MB. Coverage reports omissions and malformed input. Other evidence shapes can
require focused calls. Live pages are not a database snapshot and should be
revisited after the run completes. CI must provide a run ID or metadata identifying
one execution; ambiguity is reported instead of choosing the newest run.

Reproduce from `server/stove-server` after the
[benchmark setup](../acceptance-tests/README.md#mcp-failure-evidence-benchmark):

```shell
cargo build --release --locked --bin stove
target/mcp-benchmark-venv/bin/python scripts/benchmark-mcp.py \
  --suite diagnosis --output target/mcp-benchmark/diagnosis
```

[Recorded measurements](mcp-diagnose-2026-09-07.json) include binary identity,
environment, all case summaries, cause checks, loop results and nine successful
protocol/tool error probes. Raw timings and response examples are generated in
the output directory.

Validation: **122 tests passed** (95 library, 6 diagnosis endpoint, 14 existing MCP,
6 focused evidence, 1 real-server acceptance). Formatting, strict Clippy across
all targets and diff checks passed. PostgreSQL load and actual agent/model
evaluation remain outside this local benchmark.
