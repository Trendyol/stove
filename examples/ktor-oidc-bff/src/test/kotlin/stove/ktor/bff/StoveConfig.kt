package stove.ktor.bff

import com.trendyol.stove.extensions.kotest.StoveKotestExtension
import com.trendyol.stove.http.*
import com.trendyol.stove.ktor.KtorApplicationUnderTest
import com.trendyol.stove.oidc.*
import com.trendyol.stove.postgres.*
import com.trendyol.stove.process.*
import com.trendyol.stove.system.*
import com.trendyol.stove.system.abstractions.SystemKey
import io.kotest.core.config.AbstractProjectConfig
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.serialization.jackson.jackson
import stove.ktor.bff.fixtures.BffWithResourceApi
import stove.ktor.bff.fixtures.keycloakProvider

object IdentityProvider : SystemKey
object OrdersApi : SystemKey

class StoveConfig : AbstractProjectConfig() {
  override val extensions = listOf(StoveKotestExtension())

  override suspend fun beforeProject() {
    Stove().with {
      if (postgres) {
        postgresql {
          PostgresqlOptions(
            container = PostgresqlContainerOptions(
              tag = "17-alpine",
              // Default fault injection resolves by image; concurrent suites use the same image.
              useContainerFn = { image ->
                object : StovePostgresqlContainer(image) {
                  override val containerIdAccess: String get() = containerId
                }
              }
            ),
            configureExposedConfiguration = {
              postgresSettings = mapOf(
                "BFF_SESSION_STORE" to "postgres",
                "BFF_SESSION_JDBC_URL" to it.jdbcUrl,
                "BFF_SESSION_DB_USER" to it.username,
                "BFF_SESSION_DB_PASSWORD" to it.password
              )
              postgresSettings.map { (name, value) -> "$name=$value" }
            }
          )
        }
      }
      oidc {
        OidcSystemOptions(
          provider = if (keycloak) keycloakProvider(origin) else OidcProvider.Mock(port = providerPort, configure = { copy(rotateRefreshToken = true) }),
          configureExposedConfiguration = {
            providerOrigin = io.ktor.http.Url(it.issuerUrl).let { url -> "${url.protocol.name}://${url.host}:${url.port}" }
            listOf("OIDC_ISSUER=${it.issuerUrl}")
          }
        )
      }
      httpClient { browserHttp { origin } }
      httpClient(IdentityProvider) { browserHttp { providerOrigin } }
      httpClient(OrdersApi) { browserHttp { resourceOrigin } }

      val executable = System.getProperty("bff.native.executable", "")
      val bff = if (executable.isEmpty()) {
        KtorApplicationUnderTest(
          stove = stove,
          runner = { startBff(it) },
          parameters = listOf("PORT=$appPort", "BFF_ORIGIN=$origin", "OIDC_CLIENT_ID=stove-bff", "OIDC_CLIENT_SECRET=bff-example-secret", "OIDC_DPOP=$keycloak")
        )
      } else {
        ProcessApplicationUnderTest(
          ProcessApplicationOptions(
            command = listOf(executable),
            target = ProcessTarget.Server(port = appPort, portEnvVar = "PORT"),
            envProvider = envMapper {
              "OIDC_ISSUER" to "OIDC_ISSUER"
              "RESOURCE_API_URL" to "RESOURCE_API_URL"
              "BFF_ROUTES_FILE" to "BFF_ROUTES_FILE"
              if (postgres) {
                "BFF_SESSION_STORE" to "BFF_SESSION_STORE"
                "BFF_SESSION_JDBC_URL" to "BFF_SESSION_JDBC_URL"
                "BFF_SESSION_DB_USER" to "BFF_SESSION_DB_USER"
                "BFF_SESSION_DB_PASSWORD" to "BFF_SESSION_DB_PASSWORD"
              }
              env("BFF_ORIGIN", origin)
              env("OIDC_CLIENT_ID", "stove-bff")
              env("OIDC_CLIENT_SECRET", "bff-example-secret")
              env("OIDC_DPOP", keycloak.toString())
            }
          )
        )
      }
      applicationUnderTest(BffWithResourceApi(bff, requireDpop = keycloak) { resourceOrigin = it })
    }.run()
  }

  override suspend fun afterProject() = Stove.stop()

  companion object {
    val keycloak = java.lang.Boolean.getBoolean("bff.keycloak")
    val postgres = java.lang.Boolean.getBoolean("bff.postgres")
    var postgresSettings: Map<String, String> = emptyMap()
      private set
    private var providerOrigin = "http://localhost"
    var resourceOrigin = "http://localhost"
      private set
    private val appPort = PortFinder.findAvailablePort()
    private val providerPort = PortFinder.findAvailablePort()
    val origin = "http://localhost:$appPort"

    private fun browserHttp(baseUrl: () -> String) = HttpClientSystemOptions(
      baseUrl = "http://localhost",
      // CIO leaves redirects to the test; the default OkHttp engine also follows them internally.
      createClient = { _ ->
        HttpClient(CIO) {
          defaultRequest { url(baseUrl()) }
          install(ContentNegotiation) { jackson() }
          // Database cancellation during pause faults can outlast CIO's default 15-second request timeout.
          install(HttpTimeout) { requestTimeoutMillis = 30_000 }
          followRedirects = false
          expectSuccess = false
        }
      }
    )
  }
}
