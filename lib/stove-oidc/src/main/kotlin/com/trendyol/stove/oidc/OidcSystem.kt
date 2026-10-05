package com.trendyol.stove.oidc

import com.trendyol.stove.reporting.*
import com.trendyol.stove.system.Stove
import com.trendyol.stove.system.abstractions.*
import com.trendyol.stove.system.annotations.StoveDsl

@StoveDsl
@OidcDsl
class OidcSystem internal constructor(
  override val stove: Stove,
  private val options: OidcSystemOptions,
  private val keyName: String = "default"
) : PluggedSystem, RunAware, ExposesConfiguration, Reports {
  private var lifecycle: Lifecycle = Lifecycle.Stopped
  private val diagnostics = OidcDiagnostics()
  private val providerName = if (options.provider is OidcProvider.Mock) "NAV" else "Keycloak"
  override val reportSystemName: String = "OIDC [$keyName, $providerName]"
  val endpoints: OidcEndpoints get() = running().endpoints
  val supportsMockControls: Boolean get() = options.provider is OidcProvider.Mock
  val mock: MockOidcControls
    get() {
      check(supportsMockControls) { "$reportSystemName does not support mock token controls" }
      return running().backend.mockControls(this)
    }

  override suspend fun run() {
    check(lifecycle == Lifecycle.Stopped) { "$reportSystemName already started" }
    options.validate()
    try {
      withOidcContext("startup") { startProvider() }
    } catch (error: Throwable) {
      // Roll back partial startup even on cancellation or fatal failure, then preserve that failure.
      closeAfterFailure(error)
      throw error
    }
  }

  private suspend fun startProvider() {
    val runtime = options.provider.createBackend()
    lifecycle = Lifecycle.Starting(runtime)
    val issuer = runtime.start()
    val client = OidcProtocolClient(options)
    lifecycle = Lifecycle.Discovering(runtime, client)
    val discovered = client.awaitReady(issuer)
    lifecycle = Lifecycle.Running(runtime, client, discovered)
    reporter.addListener(runtime.testLifecycle)
  }

  override fun configuration(): List<String> = options.configureExposedConfiguration(endpoints)

  suspend fun token(request: TokenRequest): OidcTokenResponse = operation("token ${request.grantType()}") {
    val current = running()
    current.protocol.token(current.endpoints.tokenEndpoint, request, reporter.currentTestIdOrNull())
  }

  suspend fun <T> mock(block: suspend MockOidcControls.() -> T): T = block(mock)

  internal suspend fun <T> operation(name: String, block: suspend () -> T): T =
    report(action = name) { diagnostics.record(name, block) }

  override fun snapshot(): SystemSnapshot {
    val current = lifecycle
    val details = if (current is Lifecycle.Running) diagnostics.state(current.endpoints) else diagnostics.state()
    return SystemSnapshot(
      system = reportSystemName,
      state = details + mockEvidence(current) + mapOf(
        "running" to (current is Lifecycle.Running),
        "provider" to providerName,
        "mockControls" to supportsMockControls
      ),
      summary = "$providerName: ${diagnostics.summary()}"
    )
  }

  private fun mockEvidence(current: Lifecycle): Map<String, Any> =
    if (current is Lifecycle.Running) current.backend.evidence(reporter.currentTestId()) else emptyMap()

  override suspend fun stop(): Unit = close()

  override fun close() {
    val previous = lifecycle
    lifecycle = Lifecycle.Stopped
    if (previous !is Lifecycle.Active) return
    withOidcContext("shutdown") {
      previous.use { reporter.removeListener(previous.backend.testLifecycle) }
    }
  }

  private fun closeAfterFailure(failure: Throwable) {
    try {
      close()
    } catch (cleanupFailure: Throwable) {
      if (cleanupFailure !== failure) failure.addSuppressed(cleanupFailure)
    }
  }

  private fun running(): Lifecycle.Running {
    val current = lifecycle
    check(current is Lifecycle.Running) { "$reportSystemName is not running" }
    return current
  }

  private sealed interface Lifecycle {
    data object Stopped : Lifecycle
    sealed interface Active : Lifecycle, AutoCloseable {
      val backend: OidcBackend
      override fun close() = backend.close()
    }
    sealed interface Connected : Active {
      val protocol: OidcProtocolClient
      override fun close() = backend.use { protocol.close() }
    }
    class Starting(override val backend: OidcBackend) : Active
    class Discovering(override val backend: OidcBackend, override val protocol: OidcProtocolClient) : Connected
    class Running(
      override val backend: OidcBackend,
      override val protocol: OidcProtocolClient,
      val endpoints: OidcEndpoints
    ) : Connected
  }
}
