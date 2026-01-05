# Reporting

Stove provides a comprehensive reporting system that tracks all actions and assertions during test execution. When a test fails, you get detailed information about what happened, making debugging easier.

## Features

- **Automatic tracking** of all system interactions (HTTP, Kafka, database, etc.)
- **Test failure enrichment** with detailed execution reports
- **Multiple output formats** (console, JSON)
- **Framework integration** with Kotest and JUnit

## Quick Start

### Kotest Integration

Add the `StoveKotestExtension` to your project configuration:

```kotlin
class TestConfig : AbstractProjectConfig() {
    override val extensions: List<Extension> = listOf(StoveKotestExtension())
    
    override suspend fun beforeProject() {
        TestSystem()
            .with {
                // your configuration
            }
            .run()
    }
    
    override suspend fun afterProject() {
        TestSystem.stop()
    }
}
```

### JUnit Integration

Add the `StoveJUnitExtension` to your test classes:

```kotlin
@ExtendWith(StoveJUnitExtension::class)
class MyE2ETest {
    // your tests
}
```

## Configuration

Configure reporting options in your `TestSystem` setup:

```kotlin
TestSystem {
    reporting {
        enabled()           // Enable reporting (default: true)
        dumpOnFailure()     // Dump report when tests fail (default: true)
        failureRenderer(PrettyConsoleRenderer)  // Set the renderer
    }
}.with {
    // your configuration
}.run()
```

Or use the direct methods:

```kotlin
TestSystem {
    reportingEnabled(true)
    dumpReportOnTestFailure(true)
    failureRenderer(PrettyConsoleRenderer)
}.with {
    // your configuration
}.run()
```

## What Gets Reported

### Actions

Every interaction with a Stove system is recorded:

- **HTTP**: All requests and responses (GET, POST, PUT, DELETE, etc.)
- **Kafka**: Message publishing, consumption, and failure assertions
- **Database**: Queries, saves, deletes (Couchbase, PostgreSQL, MongoDB, etc.)
- **WireMock**: Stub registrations and verifications
- **gRPC**: Client connections and calls

### Assertions

Both successful and failed assertions are tracked:

- Expected vs. actual values
- Assertion descriptions
- Error messages

## Example Output

When a test fails, you'll see output like this:

```
expected:<2> but was:<1>

═══════════════════════════════════════════════════════════════════════════════
                         STOVE EXECUTION REPORT
═══════════════════════════════════════════════════════════════════════════════

╔══════════════════════════════════════════════════════════════════════════════╗
║                           STOVE TEST REPORT                                  ║
║ Test: ExampleTest::should save the product                                   ║
╠══════════════════════════════════════════════════════════════════════════════╣
║ 14:47:38.215 ✓ PASSED [HTTP] POST /api/products                              ║
║     Input: {"id":1234,"name":"Test Product"}                                 ║
║     Output: 201 Created                                                      ║
║                                                                              ║
║ 14:47:38.341 ✗ FAILED [PostgreSQL] Query                                     ║
║     Input: SELECT * FROM Products WHERE id=1234                              ║
║     Output: 1 row(s) returned                                                ║
║     Expected: 2                                                              ║
║     Actual: 1                                                                ║
║     Error: expected:<2> but was:<1>                                          ║
╚══════════════════════════════════════════════════════════════════════════════╝
```

## Renderers

Stove provides two built-in renderers:

### PrettyConsoleRenderer (Default)

Human-readable format with:

- Colorized output (when terminal supports ANSI)
- Box-drawing characters for structure
- Timestamps for each action
- Clear pass/fail indicators

### JsonReportRenderer

Machine-readable JSON format, useful for:

- CI/CD integration
- Log aggregation systems
- Custom report processing

```kotlin
TestSystem {
    failureRenderer(JsonReportRenderer)
}
```

Example JSON output:

```json
{
  "testId": "ExampleTest::should save the product",
  "testName": "should save the product",
  "entries": [
    {
      "type": "action",
      "system": "HTTP",
      "action": "POST /api/products",
      "timestamp": "2025-01-05T14:47:38.215",
      "result": "PASSED",
      "input": {"id": 1234, "name": "Test Product"},
      "output": "201 Created"
    },
    {
      "type": "action_with_result",
      "system": "PostgreSQL",
      "action": "Query",
      "timestamp": "2025-01-05T14:47:38.341",
      "result": "FAILED",
      "expected": 2,
      "actual": 1,
      "error": "expected:<2> but was:<1>"
    }
  ],
  "summary": {
    "totalActions": 2,
    "totalAssertions": 0,
    "passedAssertions": 0,
    "failedAssertions": 1
  }
}
```

## System Snapshots

Some systems provide state snapshots when tests fail, giving you visibility into the system's internal state:

### Kafka Snapshot

Shows all messages in the message store:

```
╔══════════════════════════════════════════════════════════════════════════════╗
║ ┌─ KAFKA ────────────────────────────────────────────────────────────────────║
║                                                                              ║
║   Consumed: 1                                                                ║
║   Produced: 1                                                                ║
║   Failed: 0                                                                  ║
║                                                                              ║
║   State Details:                                                             ║
║     produced: 1 item(s)                                                      ║
║       [0]                                                                    ║
║         topic: product-events                                                ║
║         key: 1234                                                            ║
║         value: {"id":1234,"name":"Test Product"}                             ║
║     consumed: 1 item(s)                                                      ║
║       [0]                                                                    ║
║         topic: product-events                                                ║
║         value: {"id":1234,"name":"Test Product"}                             ║
║     failed: 0 item(s)                                                        ║
╚══════════════════════════════════════════════════════════════════════════════╝
```

### WireMock Snapshot

Shows registered stubs and unmatched requests:

```
╔══════════════════════════════════════════════════════════════════════════════╗
║ ┌─ WIREMOCK ─────────────────────────────────────────────────────────────────║
║                                                                              ║
║   Registered stubs: 2                                                        ║
║   Served requests: 1 (matched: 1)                                            ║
║   Unmatched requests: 0                                                      ║
║                                                                              ║
║   State Details:                                                             ║
║     registeredStubs: 2 item(s)                                               ║
║     servedRequests: 1 item(s)                                                ║
║     unmatchedRequests: 0 item(s)                                             ║
╚══════════════════════════════════════════════════════════════════════════════╝
```

## Disabling Reporting

If you need to disable reporting (e.g., for performance-sensitive test runs):

```kotlin
TestSystem {
    reporting {
        disabled()
    }
}
```

Or:

```kotlin
TestSystem {
    reportingEnabled(false)
}
```

## Best Practices

### 1. Always Use the Extension

Register `StoveKotestExtension` or `StoveJUnitExtension` in your test configuration to get automatic test context tracking and failure enrichment.

### 2. Use Descriptive Actions

When writing custom assertions, provide meaningful descriptions:

```kotlin
shouldQuery<Product>("SELECT * FROM products WHERE active = true") { products ->
    products.size shouldBe expectedCount
}
```

### 3. Review Reports on CI

The JSON renderer is particularly useful for CI/CD pipelines. You can:

- Parse the JSON output for custom reporting
- Store reports as build artifacts
- Integrate with test management tools

## Troubleshooting

### Reports Not Showing

1. Ensure the extension is registered:
   - Kotest: `override val extensions = listOf(StoveKotestExtension())`
   - JUnit: `@ExtendWith(StoveJUnitExtension::class)`

2. Check that reporting is enabled:
   ```kotlin
   TestSystem {
       reportingEnabled(true)
       dumpReportOnTestFailure(true)
   }
   ```

3. Verify `TestSystem` is initialized before tests run

### Truncated Output

If output appears truncated in your console, try:

- Using a wider terminal window
- Switching to `JsonReportRenderer` for full output
- Checking your logging configuration
