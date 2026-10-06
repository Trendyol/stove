package stove.ktor.bff.tests

import com.trendyol.stove.oidc.oidc
import com.trendyol.stove.system.abstractions.ApplicationUnderTest
import com.trendyol.stove.system.stove
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.http.Url
import kotlinx.coroutines.CancellationException
import stove.ktor.bff.fixtures.BffWithResourceApi
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.Socket

class ResourceApiLifecycleTest : FunSpec({
  test("composition starts the API before the BFF, preserves its context, and closes the listener") {
    stove {
      val bff = RecordingBff()
      val runner = BffWithResourceApi(bff, requireDpop = true) { bff.apiOrigin = it }
      try {
        runner.start(listOf("OIDC_ISSUER=${oidc { endpoints.issuerUrl }}")) shouldBe "bff-context"
      } finally {
        runner.stop()
      }
      runner.stop()
      bff.stopCalls shouldBe 1
      shouldThrow<ConnectException> { connectToFixture(bff.apiOrigin) }
    }
  }

  val startupFailures = mapOf(
    "failed" to IllegalStateException("startup failed"),
    "cancelled" to CancellationException("startup cancelled")
  )
  for ((name, failure) in startupFailures) {
    test("$name BFF startup closes the API even when BFF cleanup also fails") {
      stove {
        lateinit var origin: String
        val cleanupFailure = IllegalStateException("cleanup failed")
        val bff = FailingBff(failure, cleanupFailure)
        val runner = BffWithResourceApi(bff, requireDpop = true) { origin = it }
        try {
          val thrown = shouldThrow<Exception> { runner.start(listOf("OIDC_ISSUER=${oidc { endpoints.issuerUrl }}")) }
          thrown shouldBe failure
          thrown.suppressed.toList() shouldBe listOf(cleanupFailure)
          shouldThrow<ConnectException> { connectToFixture(origin) }
        } finally {
          runner.stop()
        }
        bff.stopCalls shouldBe 1
      }
    }
  }
})

private class RecordingBff : ApplicationUnderTest<String> {
  lateinit var apiOrigin: String
  var stopCalls = 0
    private set

  override suspend fun start(configurations: List<String>): String {
    configurations.single { it.startsWith("RESOURCE_API_URL=") } shouldBe "RESOURCE_API_URL=$apiOrigin"
    connectToFixture(apiOrigin)
    return "bff-context"
  }

  override suspend fun stop() {
    connectToFixture(apiOrigin)
    stopCalls++
  }
}

private class FailingBff(
  private val startupFailure: Exception,
  private val cleanupFailure: Exception
) : ApplicationUnderTest<Unit> {
  var stopCalls = 0
    private set

  override suspend fun start(configurations: List<String>): Unit = throw startupFailure

  override suspend fun stop() {
    stopCalls++
    throw cleanupFailure
  }
}

// These independently created lifecycle fixtures have no registered Stove HTTP client.
// A socket probe specifically verifies that teardown releases the listening port.
private fun connectToFixture(origin: String) {
  val url = Url(origin)
  Socket().use { it.connect(InetSocketAddress(url.host, url.port), 500) }
}
