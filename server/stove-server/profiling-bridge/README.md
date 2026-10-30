# Embedded JFR converter

Build dependency: Go (see go.mod) and a C compiler. Cargo build.rs builds this
package as a C archive and links it into Stove. Supported targets are Linux and
macOS, amd64 and arm64. No separate process or Go installation is needed at runtime.

Grafana jfr-parser v0.19.0 owns JFR parsing, stack reconstruction, sample semantics,
and the Pyroscope labels protobuf contract. main.go adapts its pprof results into
a small internal JSON representation and exports malloc-owned bytes over a C ABI.
Rust frees every result using StoveProfileFree.
The representation interns frames in a recording-wide table; samples reference
that table by index. Rust keeps shared frame ownership during aggregation and
expands only the stacks returned to clients. Previously stored inline frames
remain readable. Conversion failures preserve the parser's error message.

The HTTP boundary limits inputs and concurrency. The adapter bounds normalized
samples/frames/output; these are not hard RSS or execution-time limits on the
upstream parser. Keep the dependency pinned, and exercise real fixtures when
upgrading. This module does not embed the Pyroscope server or implement its full
query/ingestion API.

See THIRD_PARTY_NOTICES for the embedded dependencies and their licenses.
