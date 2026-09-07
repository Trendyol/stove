# Stove server refactoring review

The refactoring keeps the existing Rust service and React/TanStack Query architecture,
while tightening contracts and separating orchestration, data transformation, and
presentation. All four follow-up priorities from the initial review are implemented.

## Implemented changes

| Area | Change | Result |
| --- | --- | --- |
| API contracts | Generate TypeScript from the Rust OpenAPI document; use generated REST request/response types and SSE event variants throughout the SPA. | Rust owns the contract. Generation caught the previously omitted `MetaResponse.mcp` field. CI rejects stale generated types. |
| Response validation | Validate unknown REST JSON and SSE payloads with small field validators checked against their TypeScript shapes. | Missing fields, invalid statuses, and malformed nested values become visible errors. Nullable response fields are explicitly required in OpenAPI, matching serialization. |
| Rust domain data | Parse stored records through fallible conversions; use separate run, test, and span status enums. Validate incoming statuses before commit. | Unknown statuses and malformed structured columns no longer become fabricated defaults. Invalid ingestion produces neither persisted evidence nor an outbox event. |
| Live events | Move event models into `ingest/events.rs`; serialize a tagged payload; derive its event label. Return preparation results directly. | Event labels cannot disagree with payload variants. Orchestration reads as prepare, commit, notify, acknowledge. |
| Live cache | Separate the owned buffer, record updates, and pure reconciliation. Share assertion-retry merging. | Published cache arrays and records remain immutable; a burst writes each affected query once. |
| Query contracts | Couple query keys, loaders, and reconciliation functions in typed descriptors. | Reconciliation no longer infers result types from string keys or casts between unrelated arrays. Compile-time tests reject incompatible records. |
| Dashboard requests | Preserve selections while relevant requests are pending or failed; clear missing selections only after successful settled results. Show loading, errors, and retry. | Temporary request failures do not discard the user's selection. |
| Admin requests | Use queries for server state and mutations for commands; retain local form drafts. Discard obsolete previews/results and reset dashboard caches after destructive writes. | Late responses cannot restore obsolete purge targets or SQL results. Deleted evidence is not reintroduced by cache reconciliation. |
| Status display | Use OpenTelemetry span statuses and make failed children take precedence in group summaries. | Valid spans remain visible, and a running child cannot hide a failure. |
| Graph and styles | Separate timeline construction, trace construction, grouping, and layout; split CSS by feature in its original order. | Smaller responsibility-focused modules preserve the existing presentation. |

## Contract generation

From `server/stove-server/spa`, install dependencies with `npm ci`. Every
`npm run build` now exports Rust's OpenAPI document and regenerates
`src/api/generated/schema.ts` **before** TypeScript checking and Vite bundling.
The generator creates missing output directories, so no initial SPA build is needed.
Unchanged generated files are not rewritten.

- `npm run generate:api` regenerates types without bundling the SPA.
- `npm run check:api` compares against Rust and fails on drift without updating types.
- `just build` installs SPA dependencies, regenerates and bundles the SPA, then builds Rust.

After editing Rust response models, live event payloads, or route annotations, build and update callers
and runtime validators together. Commit the generated file with the contract change.
CI checks drift **before** its regenerating build so stale checked-in types still fail.
CI, release workflows, and Docker provide Rust, Node, `protoc`, and native database/TLS
build prerequisites before building the SPA. No running server or database is needed.

Type generation runs a Cargo example with `SKIP_SPA_BUILD=1`. Cargo's own internal
SPA hook invokes `build:assets`, which only checks TypeScript and bundles assets;
it cannot invoke the generator while Cargo holds its build lock. Use `npm run build`
or `just build` for automatic regeneration; direct `cargo build` uses the existing
generated types. `build:assets` is an internal step, not the full frontend build.

The generator lives in a small npm tooling workspace because `openapi-typescript`
uses the TypeScript 5 compiler API while the SPA uses TypeScript 7. This avoids peer
dependency overrides and does not change the SPA compiler or runtime dependencies.
Generated code is excluded from manual formatting. Request bodies use the generated
schemas; the existing small fetch client remains in place.

The OpenAPI components now include the actual Rust `LiveDashboardEvent` and its
nine tagged payload variants. SSE transport stays `text/event-stream`; each data
field contains that JSON event. TypeScript derives event tags, payload selection,
and readonly views from the generated union, with no handwritten wire interfaces.
Nullable payload fields are required in the schema, matching Rust serialization.
Generated TypeScript provides compile-time checking; runtime validators still own
validation of actual network data. The fetch wrapper remains handwritten: it owns
HTTP requests, errors, cancellation, and response validation; its URL strings are
not generated or statically checked against OpenAPI paths.

## Unused code cleanup

Removed unused SPA methods `getRun`, `getRunMockInteractions`, and
`getRunMockWarnings`, plus the unused metadata parameter on `getRuns`. Removed
`filteredRuns` and its live-cache branch; the UI already filters metadata locally.
Public backend endpoints remain documented and covered by API tests, including
run-wide evidence access that the SPA does not use.

Removed the unused `EntryRow` component, `collectStatuses`, nullable-response helper,
redundant record-ID alias/validator, and unused JSON formatting/preview helpers.
The single-event cache wrapper is now a test helper; production uses the batch API.
Helpers and frontend-only types used within one module are private. Generated type
aliases remain where they make call sites
readable; UI props, form state, graph models, and worker protocols remain frontend
contracts rather than being forced into backend schemas.

## Compatibility policy

No compatibility layer was added. Stored unknown statuses, invalid JSON in structured
columns, and empty assertion IDs now fail with a column-specific error. SQLite and
PostgreSQL use actual assertion IDs instead of synthesizing legacy identities. SPA
record IDs are numbers, matching Rust serialization, rather than string-or-number
unions. Existing corrupt data must be corrected or removed before affected reads can
succeed; this change does not silently migrate it.

Additional object fields are allowed by the validators, while required fields and
values remain strict. SSE parsing projects validated fields before cache updates.
Nullable fields continue to represent legitimate absence. No database migration or
new application runtime dependency was introduced.

## Re-evaluation

The changes use single responsibility and narrow typed contracts without adding a
repository abstraction hierarchy, reducer framework, generated runtime client, or
new routing library. Data declarations stay together where that makes a contract
easier to inspect; functions are separated by responsibility rather than line count.

Local mutation is confined to owned temporary arrays and maps in the cache buffer
and graph construction. Public reconciliation functions accept read-only inputs and
return new results. This preserves observable immutability without copying a growing
array once per event.

The second review checked request races and cache invalidation, including edits made
while retention settings refresh, late purge previews, late SQL results, and stale
REST reads racing with live events. It also removed redundant status wrappers and
unsafe reconciliation casts. The final pass added direct coverage for corrupt entry
and span records and made live entry results use the test-status enum.

## Verification

- SPA: 75 tests, including REST boundary checks and real hooks under React StrictMode;
  compile-time query and SSE contract tests; TypeScript production build; Biome check.
- Rust: 85 library tests, including all nine live variants checked against generated
  schema fields and nullability; 54 REST/SSE tests and 9 MCP tests.
- All 5 acceptance tests: SQLite, PostgreSQL, shared-server ingestion and replay,
  retention/admin operations, and embedded SPA serving.
- PostgreSQL load test: 50,000 runs, 120 requests, concurrency 12; overall p95 657 ms
  against a 2,000 ms budget. These are local measurements, not a production guarantee.
- Formatting and Clippy for all Rust targets with warnings denied; generated API
  drift check; reproducible npm installation and production build.
- Automatic build checks: missing generated types and empty assets are rebuilt;
  stale generated types fail the CI check; unchanged types retain their modification
  time; generator failure stops compilation without replacing existing output.
  Both `just build` and the internal Cargo asset hook complete without recursion.
- Docker image build and runtime smoke check: API metadata, OpenAPI, embedded HTML,
  and generated JavaScript/CSS assets all served successfully. CI and release
  workflow syntax and prerequisite ordering were checked locally.
- Headless Chrome checks at 1440×1000 and 390×844: populated dashboard, desktop flow
  graph, navigation to admin, and SQL execution; no browser runtime errors. Browser
  checks use mocked API fixtures; acceptance tests exercise the real server.

The CSS extraction was verified to preserve rule order before formatting. Desktop
and mobile screenshots were inspected; dashboard, flow, and mobile admin captures
matched the earlier baseline. Biome passes with 14 warnings about existing selector
specificity and unused suppressions; this refactoring does not reorder those rules.
