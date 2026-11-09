# Quarkus startup integration tests

This fixture builds a headless Quarkus fast-jar with no HTTP extension or custom
readiness signal. Stove must detect startup through the packaged application's
classloader, wait for its startup observer, and shut it down without exiting the
test JVM. Tests also cover repeated startup and an observer that fails.

Run from the repository root:

```bash
./gradlew :starters:quarkus:tests:quarkus-startup:test
```

The companion `quarkus-bridge` fixture covers the augmented uber-jar and shared
classpath. Starter tests cover delayed initialization, misleading HTTP responses
and legacy signals, early runner exit, and startup timeout handling.
