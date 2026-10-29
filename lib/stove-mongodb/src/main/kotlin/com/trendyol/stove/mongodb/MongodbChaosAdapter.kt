package com.trendyol.stove.mongodb

import com.mongodb.ConnectionString
import com.mongodb.ServerAddress
import com.trendyol.stove.chaos.ChaosEndpointAdapter
import com.trendyol.stove.chaos.TcpEndpoint
import java.net.URI

/** Pins the application driver to the proxy instead of following replica-set advertisements. */
internal object MongodbChaosAdapter : ChaosEndpointAdapter<MongodbExposedConfiguration> {
  override fun endpoint(configuration: MongodbExposedConfiguration): TcpEndpoint {
    validate(configuration.connectionString, configuration)
    validate(configuration.replicaSetUrl, configuration)
    return TcpEndpoint(configuration.host, configuration.port)
  }

  override fun applicationConfiguration(
    direct: MongodbExposedConfiguration,
    endpoint: TcpEndpoint
  ): MongodbExposedConfiguration {
    this.endpoint(direct)
    return direct.copy(
      host = endpoint.host,
      port = endpoint.port,
      connectionString = route(direct.connectionString, endpoint),
      replicaSetUrl = route(direct.replicaSetUrl, endpoint)
    )
  }

  private fun validate(value: String, configuration: MongodbExposedConfiguration) {
    require(value.startsWith("mongodb://")) {
      "MongoDB chaos requires a single mongodb:// endpoint; SRV discovery is unsupported"
    }
    val parsed = ConnectionString(value)
    require(parsed.hosts.size == 1) { "MongoDB chaos does not support multiple seed hosts" }
    require(parsed.isLoadBalanced != true) { "MongoDB chaos does not support load-balanced connections" }
    require(parsed.sslEnabled != true) {
      "MongoDB chaos does not support TLS: the proxy hostname cannot preserve server identity verification"
    }
    val address = ServerAddress(parsed.hosts.single())
    require(address.host.equals(configuration.host, ignoreCase = true) && address.port == configuration.port) {
      "MongoDB chaos requires both connection URLs to match the configured host and port"
    }
  }

  private fun route(value: String, endpoint: TcpEndpoint): String {
    val uri = URI(value)
    val credentials = uri.rawUserInfo?.let { "$it@" }.orEmpty()
    return "mongodb://$credentials${endpoint.authority}${uri.rawPath.ifEmpty { "/" }}?${directConnectionQuery(uri)}"
  }

  private fun directConnectionQuery(uri: URI): String {
    val retainedOptions = uri.rawQuery.orEmpty().split('&')
      .filter { it.isNotEmpty() && !it.substringBefore('=').equals("directConnection", ignoreCase = true) }
    return (retainedOptions + "directConnection=true").joinToString("&")
  }
}
