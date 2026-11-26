# Unreleased

## One Stove Gradle plugin

Replace the plugin ID `com.trendyol.stove.tracing` with `com.trendyol.stove` and the implementation artifact `com.trendyol:stove-tracing-gradle-plugin` with `com.trendyol:stove-gradle-plugin`. The new release does not publish aliases for the old names.

**Applying the new plugin alone no longer activates tracing.** Add `stove { tracing {} }` even if the old build never configured `stoveTracing`. Move `serviceName` and `testTaskNames` to `stove`, OTel options into `tracing`, and Pyroscope options into its sibling `profiling`. For profiling alone, use `stove { profiling {} }`. Runtime Stove registration is unchanged.

The new [WireMock contract DSL](../Components/04-wiremock.md#typed-mocks-from-openapi) generates Kotlin stubs and request matchers from an OpenAPI file, URL, or inline spec.

Gradle tracks the effective tracing and profiling settings and agent JARs as test inputs. Changing a service name or profiling interval reruns the affected test tasks. WireMock-only builds do not attach either agent, and agent-only builds do not resolve OpenAPI Generator.
