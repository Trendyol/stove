# Version Compatibility and Upgrades

Use these notes when the project's resolved Stove version and failure signature match. Check dependency resolution before changing versions.

## Maintaining legacy suites

An existing `TestSystemConfig` name does not establish the Stove version. Inspect
its imports and the owning test task's runtime dependencies. Pre-0.20 suites can
legitimately use `TestSystem`, `com.trendyol.stove.testing.e2e.*`, and older option
or serialization APIs. For a focused fix, use the APIs supplied by that release;
do not combine current snippets with old dependencies or turn maintenance into
an unrequested upgrade.

For an upgrade, migrate the BOM/artifacts, imports, runner and system options,
serialization, and framework lifecycle together, following the intervening
release notes. Compile the affected source set before running it. A modern class
name or successful import replacement alone does not verify a migration.

## Version reporting

Stove reports the exact published coordinate of the artifact, not a separate "compatibility
number": `0.28.0` for a release, `1.0.0.566-SNAPSHOT` for a snapshot. Dashboard runs and apps
expose it as `stove_version`; the server exposes its own as `stove_server_version` in
`/api/v1/meta`, `/api/v1/health`, and the OpenAPI `info.version`.

Snapshot coordinates are valid for comparison. The dashboard compares numeric components, so a
client and server from the same build (including the same `-SNAPSHOT`) show no mismatch. An older
runtime against a newer server (for example `0.27.6` vs `0.28.0`) still warns. If a stored run has
no version, the UI renders "not reported" rather than guessing one.

When a version-mismatch banner appears, align the Stove BOM and all Stove test dependencies with
the server build. Do not edit `gradle.properties` to silence the warning.
## Unreleased database setup and chaos migration

For receiver-based setup callbacks, removed zero-argument registration overloads, and managed database chaos, use
[the focused migration guide](setup-chaos-migration.md). These changes are unreleased after `0.27.6`; verify the resolved
artifact before migrating.

## Known runtime pitfalls (0.25.x)

Stove 0.25+ compiles against kotlinx-coroutines 1.11 and Ktor 3.5. Two failure signatures to recognize (full details in [0.25.0 release notes](https://github.com/Trendyol/stove/blob/main/docs/release-notes/0.25.0.md)):

- `NoSuchMethodError: ... BuildersKt.runBlockingK$default` — the test runtime resolved an older coroutines (usually the Spring Boot BOM pinning 1.8.1, which overrides `resolutionStrategy.force`). Fix for Spring dependency-management users: `extra["kotlin-coroutines.version"] = "1.11.0"` in the module's `build.gradle.kts`; otherwise force via `resolutionStrategy.eachDependency`.
- Multipart stub mismatches — Ktor 3.5 quotes `Content-Disposition` names (`name="file"`). Update exact-match WireMock multipart expectations to the quoted form.

## Behavior changes in 0.26.0

Full details and migration guide in [0.26.0 release notes](https://github.com/Trendyol/stove/blob/main/docs/release-notes/0.26.0.md). Signatures to recognize when a suite upgrades:

- Kafka tests asserting partition metadata start flaking — `publish` no longer pins partition 0 by default; the configured partitioner decides. Pin `partition` explicitly or use a message key.
- `validate()` newly failing on "unmatched requests" — it now sees untagged and previously-invisible traffic (unknown gRPC methods, bidi calls, headerless HTTP). The failure includes near-miss diffs naming why each candidate stub rejected; it found real unmatched traffic, don't suppress it.
- A gRPC stub that "stopped matching" after adding another — last-registered-wins precedence replaced first-match; the newer stub is intentionally overriding.
- `IllegalArgumentException` at stub registration — mixed RPC types on one method, or a `requestMatcher` on a bidi stub; both now fail fast instead of misbehaving silently.
