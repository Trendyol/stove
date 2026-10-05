package com.trendyol.stove.oidc

import arrow.core.getOrElse
import arrow.core.some
import com.nimbusds.jwt.SignedJWT
import com.trendyol.stove.system.Stove
import dasniko.testcontainers.keycloak.KeycloakContainer
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.keycloak.representations.idm.ClientRepresentation
import org.keycloak.representations.idm.CredentialRepresentation
import org.keycloak.representations.idm.UserRepresentation
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.Network
import java.net.CookieManager
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

class KeycloakOidcTest : FunSpec({
  test("missing realm resources identify the fixture path") {
    val failure = shouldThrow<OidcConfigurationException> {
      withProvider(OidcProvider.Keycloak(setup = RealmSetup.Classpath("missing-test-realm.json"))) { }
    }
    failure.field shouldBe "setup"
    failure.message.orEmpty() shouldContain "Realm resource not found"
    failure.message.orEmpty() shouldContain "missing-test-realm"
    failure.cause shouldBe null
  }

  test("realm imports precede the managed admin callback") {
    var configured = false
    withProvider(
      OidcProvider.Keycloak(
        realm = "imported",
        setup = RealmSetup.Classpath("oidc-realm.json"),
        configureAdmin = { name ->
          realm(name).clients().findByClientId("imported-service").size shouldBe 1
          configured = true
        }
      )
    ) {
      configured shouldBe true
      val result = token(TokenRequest.ClientCredentials(OidcClient("imported-service", ClientAuthentication.SecretPost("imported-secret"))))
      jwtProcessor().process(result.accessToken.value, null).issuer shouldBe endpoints.issuerUrl
    }
  }

  test("Keycloak uses real client policy, code with PKCE and refresh tokens through the shared API") {
    withProvider(loginProvider()) {
      for (auth in listOf(ClientAuthentication.SecretPost("test-secret"), ClientAuthentication.SecretBasic("test-secret"))) {
        val tokens = token(TokenRequest.ClientCredentials(OidcClient("service", auth)))
        jwtProcessor().process(tokens.accessToken.value, null).issuer shouldBe endpoints.issuerUrl
      }
      val error = shouldThrow<OidcTokenEndpointException> {
        token(TokenRequest.ClientCredentials(OidcClient("service", ClientAuthentication.SecretPost("wrong"))))
      }
      error.oauthError shouldBe "unauthorized_client"
      var blockExecuted = false
      shouldThrow<IllegalStateException> { mock { blockExecuted = true } }
      blockExecuted shouldBe false
      shouldThrow<IllegalStateException> { mock }

      val verifier = "v".repeat(43)
      val browser = KeycloakLoginBrowser(endpoints.authorizationEndpoint, verifier)
      val code = browser.login()
      val tokens = token(TokenRequest.AuthorizationCode(OidcClient("web"), code, "http://localhost/callback", verifier.some()))
      tokens.idToken.isSome() shouldBe true
      val refreshed = token(TokenRequest.RefreshToken(OidcClient("web"), tokens.refreshToken.getOrElse { error("missing") }))
      jwtProcessor().process(refreshed.accessToken.value, null).issuer shouldBe endpoints.issuerUrl
      shouldThrow<OidcTokenEndpointException> {
        token(TokenRequest.AuthorizationCode(OidcClient("web"), code, "http://localhost/callback", verifier.some()))
      }
      val nextCode = browser.login()
      shouldThrow<OidcTokenEndpointException> {
        token(TokenRequest.AuthorizationCode(OidcClient("web"), nextCode, "http://localhost/callback", "bad".some()))
      }
    }
  }

  test("container client and host client share the canonical issuer with explicit transport mapping") {
    val network = Network.newNetwork()
    val stove = Stove()
    lateinit var keycloak: KeycloakContainer
    val system = OidcSystem(
      stove,
      OidcSystemOptions(
        provider = OidcProvider.Keycloak(
          issuerUrl = "http://oidc:8080/realms/stove".some(),
          configureContainer = {
            keycloak = this
            withNetwork(network)
            withNetworkAliases("oidc")
            withEnv("KC_HOSTNAME", "http://oidc:8080")
          },
          setup = RealmSetup.Define { clients = listOf(serviceClient("secret")) }
        ),
        resolveEndpoint = { it.replace("http://oidc:8080", keycloak.authServerUrl.trimEnd('/')) }
      )
    )
    val client = GenericContainer("python:3.13-alpine").withNetwork(network).withCommand("sleep", "300")
    try {
      system.run()
      val token = system.token(TokenRequest.ClientCredentials(OidcClient("service", ClientAuthentication.SecretPost("secret"))))
      SignedJWT.parse(token.accessToken.value).jwtClaimsSet.issuer shouldBe "http://oidc:8080/realms/stove"
      client.start()
      val result = client.execInContainer(
        "python",
        "-c",
        """
import json, urllib.request, urllib.parse, base64
issuer = 'http://oidc:8080/realms/stove'
d = json.load(urllib.request.urlopen(issuer + '/.well-known/openid-configuration'))
assert d['issuer'] == issuer
assert json.load(urllib.request.urlopen(d['jwks_uri']))['keys']
body = urllib.parse.urlencode(dict(grant_type='client_credentials', client_id='service', client_secret='secret')).encode()
t = json.load(urllib.request.urlopen(d['token_endpoint'], body))['access_token']
p = t.split('.')[1]
assert json.loads(base64.urlsafe_b64decode(p + '=' * (-len(p) % 4)))['iss'] == issuer
        """.trimIndent()
      )
      result.exitCode shouldBe 0
    } finally {
      client.stop()
      system.close()
      stove.close()
      network.close()
    }
  }
})

private fun loginProvider(): OidcProvider.Keycloak = OidcProvider.Keycloak(
  setup = RealmSetup.Define {
    clients = listOf(serviceClient("test-secret"), webClient())
    users = listOf(loginUser())
  }
)

private fun serviceClient(clientSecret: String): ClientRepresentation = serviceAccountClient("service", clientSecret)

private fun webClient(): ClientRepresentation = ClientRepresentation().apply {
  clientId = "web"
  isPublicClient = true
  isStandardFlowEnabled = true
  protocol = "openid-connect"
  redirectUris = listOf("http://localhost/callback")
  attributes = mapOf("pkce.code.challenge.method" to "S256")
}

private fun loginUser(): UserRepresentation = UserRepresentation().apply {
  username = "alice"
  isEnabled = true
  email = "alice@example.test"
  isEmailVerified = true
  firstName = "Alice"
  lastName = "Test"
  credentials = listOf(loginPassword())
}

private fun loginPassword(): CredentialRepresentation = CredentialRepresentation().apply {
  type = "password"
  value = "password"
  isTemporary = false
}

/** Minimal browser fixture; the module itself only exchanges the resulting authorization code. */
private class KeycloakLoginBrowser(private val authorizationEndpoint: String, verifier: String) {
  private val challenge = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
    java.security.MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray())
  )
  private val cookies = CookieManager(null, java.net.CookiePolicy.ACCEPT_ALL)
  private val client = HttpClient.newBuilder().cookieHandler(cookies).followRedirects(HttpClient.Redirect.NEVER).build()

  fun login(): String {
    val page = openLoginPage()
    enableLocalhostCookies()
    val action = Regex("action=\"([^\"]+)\"").find(page.body())!!.groupValues[1].replace("&amp;", "&")
    val response = submitCredentials(action)
    assertRedirect(response)
    return authorizationCode(response)
  }

  private fun openLoginPage(): HttpResponse<String> {
    val uri = "$authorizationEndpoint?response_type=code&client_id=web&redirect_uri=http%3A%2F%2Flocalhost%2Fcallback&scope=openid&state=test&code_challenge=$challenge&code_challenge_method=S256&prompt=login"
    return client.send(HttpRequest.newBuilder(URI(uri)).GET().build(), HttpResponse.BodyHandlers.ofString())
  }

  private fun enableLocalhostCookies() {
    // Browsers treat localhost as a secure context; Java's CookieManager does not.
    // This fixture uses HTTP on localhost, so emulate browser delivery of secure cookies.
    for (cookie in cookies.cookieStore.cookies) {
      cookie.version = 0
      cookie.secure = false
    }
  }

  private fun submitCredentials(action: String): HttpResponse<String> {
    val request = HttpRequest.newBuilder(URI(action))
      .header("Content-Type", "application/x-www-form-urlencoded")
      .POST(HttpRequest.BodyPublishers.ofString("username=alice&password=password"))
      .build()
    return client.send(request, HttpResponse.BodyHandlers.ofString())
  }

  private fun assertRedirect(response: HttpResponse<String>) {
    val feedback = Regex("<span[^>]*class=\"kc-feedback-text\"[^>]*>(.*?)</span>")
      .find(response.body())?.groupValues?.get(1) ?: "Login did not redirect"
    io.kotest.assertions.withClue(feedback) { response.statusCode() shouldBe 302 }
  }

  private fun authorizationCode(response: HttpResponse<String>): String {
    val location = URI(response.headers().firstValue("Location").orElseThrow())
    val code = location.rawQuery.split('&').first { it.startsWith("code=") }.substringAfter('=')
    return java.net.URLDecoder.decode(code, Charsets.UTF_8)
  }
}
