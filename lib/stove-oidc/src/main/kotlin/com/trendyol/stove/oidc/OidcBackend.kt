package com.trendyol.stove.oidc

import com.trendyol.stove.reporting.ReportEventListener

internal interface OidcBackend : AutoCloseable {
  val testLifecycle: ReportEventListener get() = NoopOidcTestLifecycle
  suspend fun start(): String
  fun evidence(testId: String): Map<String, Any> = emptyMap()
  fun mockControls(system: OidcSystem): MockOidcControls = error("OIDC provider does not support mock token controls")
}

private object NoopOidcTestLifecycle : ReportEventListener

internal enum class OidcBackendPhase { Created, Running, Closed }

internal fun OidcProvider.createBackend(): OidcBackend = when (this) {
  is OidcProvider.Mock -> MockOidcBackend(this)
  is OidcProvider.Keycloak -> KeycloakOidcBackend(this)
}
