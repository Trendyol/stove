package stove.cucumber.example

import com.trendyol.stove.dashboard.DashboardSystemOptions
import com.trendyol.stove.dashboard.dashboard
import com.trendyol.stove.http.HttpClientSystemOptions
import com.trendyol.stove.http.httpClient
import com.trendyol.stove.ktor.ktor
import com.trendyol.stove.system.PortFinder
import com.trendyol.stove.system.Stove
import com.trendyol.stove.system.stove
import com.trendyol.stove.tracing.tracing
import io.cucumber.java.After
import io.cucumber.java.AfterAll
import io.cucumber.java.BeforeAll
import io.cucumber.java.Scenario
import kotlinx.coroutines.runBlocking

object InventoryEnvironment {
  @JvmStatic
  @BeforeAll
  fun start(): Unit = runBlocking {
    val port = PortFinder.findAvailablePort()
    Stove().with {
      if (java.lang.Boolean.getBoolean("stove.dashboard.enabled")) {
        tracing { enableSpanReceiver() }
        dashboard { DashboardSystemOptions(appName = "cucumber-example") }
      }
      httpClient { HttpClientSystemOptions(baseUrl = "http://127.0.0.1:$port") }
      ktor(runner = { startInventoryApplication(port) })
    }.run()
  }

  @JvmStatic
  @AfterAll
  fun stop() = Stove.stop()
}

/** Wait for asynchronous export before Stove closes the scenario and its diagnostics. */
class InventoryObservability {
  @After
  fun verifyTraces(scenario: Scenario): Unit = runBlocking {
    if (java.lang.Boolean.getBoolean("stove.dashboard.enabled")) {
      stove {
        tracing {
          // Three requests, each with one client span and one Ktor server span.
          waitForSpans(expectedCount = 6, timeoutMs = 5000)
          if (!scenario.isFailed) {
            spanCountShouldBeAtLeast(6)
            shouldContainSpan("PUT /inventory/{sku}")
            shouldContainSpan("POST /inventory/{sku}/reserve")
            shouldContainSpan("GET /inventory/{sku}")
          }
        }
      }
    }
  }
}
