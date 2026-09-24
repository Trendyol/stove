# Version Compatibility and Upgrades

Use these notes when the project's resolved Stove version and failure signature match. Check dependency resolution before changing versions.

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
