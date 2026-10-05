package com.trendyol.stove.oidc

import arrow.core.getOrElse
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.source.ImmutableJWKSet
import com.nimbusds.jose.proc.JWSVerificationKeySelector
import com.nimbusds.jose.proc.SecurityContext
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.proc.DefaultJWTClaimsVerifier
import com.nimbusds.jwt.proc.DefaultJWTProcessor
import com.trendyol.stove.system.Stove
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

internal val http: HttpClient = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build()
internal fun get(url: String): HttpResponse<String> = http.send(
  HttpRequest.newBuilder(URI(url)).GET().build(),
  HttpResponse.BodyHandlers.ofString()
)
internal fun encoded(value: String): String = URLEncoder.encode(value, Charsets.UTF_8)

internal suspend fun <T> withProvider(
  provider: OidcProvider = OidcProvider.Mock(),
  block: suspend OidcSystem.() -> T
): T = Stove().use { stove ->
  OidcSystem(stove, OidcSystemOptions(provider)).use { system ->
    system.run()
    system.block()
  }
}

internal fun OidcSystem.jwtProcessor(audience: String? = null): DefaultJWTProcessor<SecurityContext> {
  val jwks = JWKSet.parse(get(endpoints.jwksUri).body())
  return DefaultJWTProcessor<SecurityContext>().apply {
    jwsKeySelector = JWSVerificationKeySelector(JWSAlgorithm.RS256, ImmutableJWKSet(jwks))
    jwtClaimsSetVerifier = DefaultJWTClaimsVerifier(
      audience,
      JWTClaimsSet.Builder().issuer(endpoints.issuerUrl).build(),
      setOf("sub", "exp", "iat")
    )
  }
}

internal fun OidcSystem.authorizationCode(clientId: String, verifier: String? = null): String {
  val pkce = verifier?.let { "&code_challenge=${encoded(it)}&code_challenge_method=plain" }.orEmpty()
  val response = get("${endpoints.authorizationEndpoint}?response_type=code&client_id=${encoded(clientId)}&redirect_uri=http%3A%2F%2Flocalhost%2Fcallback&scope=openid&state=stove$pkce")
  check(response.statusCode() == 302)
  val location = URI(response.headers().firstValue("Location").orElseThrow())
  return location.rawQuery.split('&').first { it.startsWith("code=") }.substringAfter('=').let {
    java.net.URLDecoder.decode(it, Charsets.UTF_8)
  }
}
