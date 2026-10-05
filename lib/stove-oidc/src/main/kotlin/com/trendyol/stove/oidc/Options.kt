package com.trendyol.stove.oidc

import arrow.core.None
import arrow.core.Option
import com.trendyol.stove.system.abstractions.*
import dasniko.testcontainers.keycloak.KeycloakContainer
import no.nav.security.mock.oauth2.OAuth2Config
import org.keycloak.representations.idm.RealmRepresentation
import java.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** One registration owns one runtime and one logical issuer. */
sealed interface OidcProvider {
  class Mock(
    val issuerId: String = "stove",
    val port: Int = 0,
    val bindAddress: String = "localhost",
    /** Canonical issuer for an explicitly configured container/proxy topology. */
    val issuerUrl: Option<String> = None,
    val clock: Clock = Clock.systemUTC(),
    val defaults: MockTokenBuilder.() -> Unit = {},
    val configure: OAuth2Config.() -> OAuth2Config = { this }
  ) : OidcProvider

  /** The admin hook runs after the selected realm setup. */
  class Keycloak(
    val realm: String = "stove",
    /** Must agree with Keycloak hostname configuration when supplied. */
    val issuerUrl: Option<String> = None,
    val image: String = "quay.io/keycloak/keycloak:26.8.0",
    val setup: RealmSetup = RealmSetup.Empty,
    val configureContainer: KeycloakContainer.() -> Unit = {},
    val configureAdmin: org.keycloak.admin.client.Keycloak.(String) -> Unit = {}
  ) : OidcProvider
}

sealed interface RealmSetup {
  data object Empty : RealmSetup
  data class Classpath(val path: String) : RealmSetup
  data class File(val path: String) : RealmSetup
  class Define(val configure: RealmRepresentation.() -> Unit) : RealmSetup
}

data class OidcEndpoints(
  val issuerUrl: String,
  val discoveryUrl: String,
  val jwksUri: String,
  val tokenEndpoint: String,
  val authorizationEndpoint: String,
  val userInfoEndpoint: Option<String> = None,
  val endSessionEndpoint: Option<String> = None
) : ExposedConfiguration

class OidcSystemOptions(
  val provider: OidcProvider = OidcProvider.Mock(),
  val requestTimeout: Duration = 10.seconds,
  val readinessTimeout: Duration = 30.seconds,
  /** Customize TLS trust or transport without changing the issuer advertised by discovery. */
  val configureHttpClient: io.ktor.client.HttpClientConfig<io.ktor.client.engine.cio.CIOEngineConfig>.() -> Unit = {},
  /** Map canonical endpoint URLs to host-reachable transport URLs. Does not rewrite metadata or token issuers. */
  val resolveEndpoint: (String) -> String = { it },
  override val configureExposedConfiguration: (OidcEndpoints) -> List<String> = { emptyList() }
) : SystemOptions,
  ConfiguresExposedConfiguration<OidcEndpoints>

internal fun OidcSystemOptions.validate() {
  requireConfiguration(requestTimeout.isPositive() && requestTimeout.isFinite(), "requestTimeout", "Timeout must be finite and positive")
  requireConfiguration(
    readinessTimeout.isPositive() && readinessTimeout.isFinite(),
    "readinessTimeout",
    "Timeout must be finite and positive"
  )
  when (val selected = provider) {
    is OidcProvider.Mock -> selected.validate()
    is OidcProvider.Keycloak -> selected.validate()
  }
}

private fun OidcProvider.Mock.validate() {
  requireConfiguration(issuerId.matches(Regex("[A-Za-z0-9_-]+")), "issuerId", "Issuer id must be one URL path segment")
  issuerUrl.onSome(::validateIssuerUrl)
  requireConfiguration(port in 0..65535, "port", "Port must be between 0 and 65535")
}

private fun OidcProvider.Mock.validateIssuerUrl(url: String) {
  val uri = httpUri(url)
  requireConfiguration(
    uri.rawPath == "/$issuerId" && uri.rawQuery == null && uri.rawFragment == null,
    "issuerUrl",
    "Mock canonical issuer must end in the configured issuer id with no query or fragment"
  )
}

private fun OidcProvider.Keycloak.validate() {
  requireConfiguration(realm.matches(Regex("[A-Za-z0-9_-]+")), "realm", "Realm must be one URL path segment")
  issuerUrl.onSome { httpUri(it) }
}
