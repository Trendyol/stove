package stove.ktor.bff.fixtures

import com.trendyol.stove.system.PortFinder
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import stove.ktor.bff.BffResources
import stove.ktor.bff.StoveConfig
import stove.ktor.bff.config.BffConfiguration
import stove.ktor.bff.gateway.BffGatewayConfiguration
import stove.ktor.bff.gateway.bffWithGateway

/** A second process-equivalent application with its own pool, clients, keys and nonce cache. */
class BffReplica private constructor(val origin: String, private val stop: () -> Unit) : AutoCloseable {
  override fun close() = stop()

  companion object {
    suspend fun start(issuer: String): BffReplica {
      val port = PortFinder.findAvailablePort()
      val settings = StoveConfig.postgresSettings + mapOf(
        "PORT" to port.toString(),
        "BFF_ORIGIN" to StoveConfig.origin,
        "OIDC_ISSUER" to issuer,
        "OIDC_DPOP" to StoveConfig.keycloak.toString(),
        "RESOURCE_API_URL" to StoveConfig.resourceOrigin
      )
      val configuration = BffConfiguration.load(emptyArray(), settings)
      val resources = BffResources.open(configuration)
      try {
        val server = embeddedServer(CIO, host = "127.0.0.1", port = port) { bffWithGateway(configuration, resources.provider, resources.storage, BffGatewayConfiguration.load(emptyArray(), settings)) }.start(false)
        return BffReplica("http://localhost:$port") {
          try {
            server.stop(0, 1_000)
          } finally {
            resources.close()
          }
        }
      } catch (failure: Throwable) {
        resources.close()
        throw failure
      }
    }
  }
}
