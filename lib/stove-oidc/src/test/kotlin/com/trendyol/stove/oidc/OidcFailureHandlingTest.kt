package com.trendyol.stove.oidc

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration.Companion.milliseconds

class OidcFailureHandlingTest : FunSpec({
  test("cancellation escapes mock rule configuration without being converted to an operation failure") {
    val cancellation = CancellationException("cancel fixture")
    withProvider {
      val error = shouldThrow<CancellationException> {
        mock { whenTokenRequested(TokenRequestMatch()) { throw cancellation } }
      }
      error shouldBe cancellation
    }
  }

  test("diagnostics distinguish cancellation from a failed operation") {
    val diagnostics = OidcDiagnostics()
    val cancellation = CancellationException("cancel operation")
    val error = shouldThrow<CancellationException> {
      diagnostics.record("issue token") { throw cancellation }
    }
    error shouldBe cancellation
    diagnostics.state()["operations"] shouldBe 1L
    diagnostics.state()["failures"] shouldBe 0L
    val operations = diagnostics.state()["recentOperations"] as List<*>
    (operations.single() as Map<*, *>)["result"] shouldBe "cancelled"
  }

  test("fatal errors propagate unchanged and are never recorded as successful operations") {
    val diagnostics = OidcDiagnostics()
    val fatal = LinkageError("provider cannot be loaded")
    val error = shouldThrow<LinkageError> {
      diagnostics.record("issue token") { throw fatal }
    }
    error shouldBe fatal
    diagnostics.state()["failures"] shouldBe 1L
    val operations = diagnostics.state()["recentOperations"] as List<*>
    (operations.single() as Map<*, *>)["result"] shouldBe "failed"
  }

  test("an operation timeout counts as failure when the calling coroutine remains active") {
    val diagnostics = OidcDiagnostics()
    shouldThrow<TimeoutCancellationException> {
      diagnostics.record("issue token") {
        withTimeout(10.milliseconds) { awaitCancellation() }
      }
    }
    diagnostics.state()["failures"] shouldBe 1L
    val operations = diagnostics.state()["recentOperations"] as List<*>
    (operations.single() as Map<*, *>)["result"] shouldBe "timed_out"
  }
})
