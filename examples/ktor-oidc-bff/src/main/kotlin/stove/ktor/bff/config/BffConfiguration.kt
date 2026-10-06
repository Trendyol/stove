package stove.ktor.bff.config

import java.net.URI

data class BffConfiguration(
  val port: Int,
  val origin: String,
  val issuer: String,
  val clientId: String,
  val clientSecret: String,
  val dpop: Boolean = false,
  val additionalScopes: Set<String> = emptySet(),
  val sessionStorage: SessionStorageConfiguration = SessionStorageConfiguration.InMemory
) {
  val callbackUrl: String = "$origin/auth/callback"
  val secureCookies: Boolean = origin.startsWith("https://")
  val scopes: String = (linkedSetOf("openid", "profile", "email") + additionalScopes).joinToString(" ")

  init {
    require(additionalScopes.all { it.isNotBlank() && it.none(Char::isWhitespace) }) { "Expected individual OAuth scopes" }
    require(port in 1..65535) { "PORT must be between 1 and 65535" }
    for (url in listOf(origin, issuer)) {
      val uri = URI(url)
      require(
        uri.scheme in setOf("http", "https") && uri.host != null && uri.userInfo == null && uri.query == null && uri.fragment == null
      ) {
        "Expected an HTTP(S) URL without credentials, query or fragment: $url"
      }
    }
    require(URI(origin).path.isEmpty()) { "BFF_ORIGIN must contain only the scheme, host and port" }
    require(clientId.isNotBlank() && clientSecret.isNotBlank()) { "OIDC_CLIENT_ID and OIDC_CLIENT_SECRET are required" }
  }

  companion object {
    fun load(args: Array<String>, environment: Map<String, String> = System.getenv()): BffConfiguration {
      val settings = environment + args.associate { it.removePrefix("--").substringBefore('=') to it.substringAfter('=') }
      val port = settings.getOrDefault("PORT", "8080").toInt()
      return BffConfiguration(
        port = port,
        origin = settings.getOrDefault("BFF_ORIGIN", "http://localhost:$port").trimEnd('/'),
        issuer = settings.getOrDefault("OIDC_ISSUER", "http://localhost:8081/realms/stove").trimEnd('/'),
        clientId = settings.getOrDefault("OIDC_CLIENT_ID", "stove-bff"),
        clientSecret = settings.getOrDefault("OIDC_CLIENT_SECRET", "bff-example-secret"),
        dpop = settings.getOrDefault("OIDC_DPOP", "false").toBooleanStrict(),
        additionalScopes = settings.getOrDefault("OIDC_SCOPES", "").split(' ').filter { it.isNotBlank() }.toSet(),
        sessionStorage = SessionStorageConfiguration.load(settings)
      )
    }
  }
}
