# Reporting

When tests fail, you want to know what went wrong. Stove's reporting system <span data-rn="highlight" data-rn-color="#00968855" data-rn-duration="800">tracks everything that happens during test execution</span>—every HTTP call, database query, Kafka message, and more. When something fails, you get a detailed report showing exactly what happened, making debugging much easier.

## What You Get

- **Automatic tracking** of all system interactions (HTTP requests, Kafka messages, database queries, etc.)
- **Rich failure reports** that show what happened before the failure
- **Multiple output formats** - human-readable console output or machine-readable JSON
- **Framework integration** with Kotest and JUnit (optional extensions)

## Quick Start

The reporting extensions are <span data-rn="underline" data-rn-color="#009688">optional but recommended</span>. They automatically enrich test failures with detailed execution reports, making debugging much easier.

### Kotest Integration

If you're using Kotest, add the extension dependency:

```kotlin hl_lines="3"
dependencies {
    testImplementation("com.trendyol:stove-extensions-kotest")
}
```

!!! info "Test Framework Extensions"
    `StoveKotestExtension` (`stove-extensions-kotest`) and `StoveJUnitExtension` (`stove-extensions-junit`) are separate packages. **Kotest** requires **6.1.3+**; **JUnit** requires **Jupiter 6.x** if possible. For Kotest, add a `kotest.properties` file with `kotest.framework.config.fqn=<your config class FQN>`. See the [Getting Started guide](../getting-started.md#step-3-create-test-configuration) for details.

Then register it in your project config:

```kotlin hl_lines="5"
import com.trendyol.stove.extensions.kotest.StoveKotestExtension
import com.trendyol.stove.system.Stove

class TestConfig : AbstractProjectConfig() {
    override val extensions: List<Extension> = listOf(StoveKotestExtension())
    
    override suspend fun beforeProject() {
        Stove()
            .with {
                // your configuration
            }
            .run()
    }
    
    override suspend fun afterProject() {
        Stove.stop()
    }
}
```

### JUnit Integration

For JUnit, add the extension dependency:

```kotlin hl_lines="3"
dependencies {
    testImplementation("com.trendyol:stove-extensions-junit")
}
```

Then annotate your test class:

```kotlin hl_lines="4 6"
import com.trendyol.stove.extensions.junit.StoveJUnitExtension
import org.junit.jupiter.api.extension.ExtendWith

@ExtendWith(StoveJUnitExtension::class)
class MyE2ETest {
    // your tests
}
```

The JUnit extension works with both JUnit 5 and 6 since they share the same Jupiter API.

## Configuration

You can configure reporting options when setting up Stove:

```kotlin hl_lines="3-5"
Stove {
    reporting {
        enabled()           // Enable reporting (default: true)
        dumpOnFailure()     // Dump report when tests fail (default: true)
        failureRenderer(PrettyConsoleRenderer)  // Set the renderer
    }
}.with {
    // your configuration
}.run()
```

Or use the direct methods if you prefer:

```kotlin hl_lines="2-4"
Stove {
    reportingEnabled(true)
    dumpReportOnTestFailure(true)
    failureRenderer(PrettyConsoleRenderer)
}.with {
    // your configuration
}.run()
```

## What Gets Reported

### Actions

<span data-rn="underline" data-rn-color="#009688">Every interaction with a Stove system is recorded:</span>

<div data-rn-group>
- **HTTP**: <span data-rn="highlight" data-rn-color="#00968855">All requests and responses</span> (GET, POST, PUT, DELETE, etc.)
- **Kafka**: <span data-rn="underline" data-rn-color="#009688">Message publishing, consumption, and failure assertions</span>
- **Database**: <span data-rn="underline" data-rn-color="#ff9800">Queries, saves, deletes</span> (Couchbase, PostgreSQL, MongoDB, etc.)
- **WireMock**: Stub registrations and verifications
- **gRPC**: Client connections and calls
</div>

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

```kotlin hl_lines="2"
Stove {
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

To use the JSON renderer:

```kotlin hl_lines="2"
Stove {
    failureRenderer(JsonReportRenderer)
}
```

## System Snapshots

Some systems provide state snapshots when tests fail, giving you <span data-rn="highlight" data-rn-color="#00968855" data-rn-duration="800">visibility into the system's internal state</span>:

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
Stove {
    reporting {
        disabled()
    }
}
```

Or:

```kotlin
Stove {
    reportingEnabled(false)
}
```

## Best Practices

### 1. Use the Extension for Better Debugging

While optional, the extensions make debugging much easier by <span data-rn="underline" data-rn-color="#009688">automatically tracking test context and enriching failures with detailed reports</span>. Just add the dependency for your test framework:

- Kotest: `testImplementation("com.trendyol:stove-extensions-kotest")`
- JUnit: `testImplementation("com.trendyol:stove-extensions-junit")`

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

If you're not seeing reports when tests fail, check these:

1. **Extension dependency added?** (optional but recommended)
   - Kotest: `testImplementation("com.trendyol:stove-extensions-kotest")`
   - JUnit: `testImplementation("com.trendyol:stove-extensions-junit")`

2. **Extension registered?**
   - Kotest: `override val extensions = listOf(StoveKotestExtension())`
   - JUnit: `@ExtendWith(StoveJUnitExtension::class)`

3. **Reporting enabled?**
   ```kotlin
   Stove {
       reportingEnabled(true)
       dumpReportOnTestFailure(true)
   }
   ```

4. **Stove initialized?** Make sure <span data-rn="box" data-rn-color="#ef5350">`Stove().run()` is called before your tests execute</span>.

### Truncated Output

If output appears truncated in your console, try:

- Using a wider terminal window
- Switching to `JsonReportRenderer` for full output
- Checking your logging configuration
