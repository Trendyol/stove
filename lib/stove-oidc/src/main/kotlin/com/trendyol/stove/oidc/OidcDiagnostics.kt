package com.trendyol.stove.oidc

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** A bounded overview of operations and discovered endpoints. */
internal class OidcDiagnostics {
  private val operations = AtomicLong()
  private val failures = AtomicLong()
  private val recentOperationsLock = ReentrantLock()
  private val recentOperations = ArrayDeque<Map<String, Any>>()

  suspend fun <T> record(name: String, block: suspend () -> T): T {
    operations.incrementAndGet()
    val started = System.nanoTime()
    var result = "failed"
    try {
      val value = withOidcContext(name) { block() }
      result = "success"
      return value
    } catch (error: CancellationException) {
      result = if (error is TimeoutCancellationException && currentCoroutineContext().isActive) "timed_out" else "cancelled"
      throw error
    } catch (error: OidcTokenEndpointException) {
      result = "${error.httpStatus}:${error.oauthError}"
      throw error
    } finally {
      if (result != "success" && result != "cancelled") failures.incrementAndGet()
      append(name, result, started)
    }
  }

  fun state(): Map<String, Any> = stateWithEndpoints(emptyMap())

  fun state(endpoints: OidcEndpoints): Map<String, Any> = stateWithEndpoints(endpointState(endpoints))

  private fun stateWithEndpoints(endpoints: Map<String, String>): Map<String, Any> = mapOf(
    "operations" to operations.get(),
    "failures" to failures.get(),
    "recentOperations" to recentOperationsLock.withLock { recentOperations.toList() },
    "endpoints" to endpoints
  )

  fun summary(): String = "${operations.get()} operations, ${failures.get()} failures"

  private fun append(name: String, result: String, started: Long) = recentOperationsLock.withLock {
    if (recentOperations.size == 20) recentOperations.removeFirst()
    recentOperations.addLast(
      mapOf("operation" to name, "result" to result, "durationMs" to (System.nanoTime() - started) / 1_000_000)
    )
  }

  private fun endpointState(endpoints: OidcEndpoints): Map<String, String> = mapOf(
    "issuer" to endpoints.issuerUrl,
    "discovery" to endpoints.discoveryUrl,
    "jwks" to endpoints.jwksUri,
    "token" to endpoints.tokenEndpoint
  )
}
