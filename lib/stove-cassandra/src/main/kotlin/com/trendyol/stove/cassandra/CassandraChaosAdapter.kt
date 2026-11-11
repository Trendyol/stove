package com.trendyol.stove.cassandra

import com.trendyol.stove.chaos.ChaosEndpointAdapter
import com.trendyol.stove.chaos.TcpEndpoint

/**
 * The Java driver retains the bootstrap endpoint for its control node but discovers peer addresses.
 * Only a dedicated, fixed single-node topology can therefore use this single TCP route.
 */
internal class CassandraChaosAdapter(
  private val hasSingleNode: () -> Boolean
) : ChaosEndpointAdapter<CassandraExposedConfiguration> {
  override fun reroute(direct: CassandraExposedConfiguration, proxy: (TcpEndpoint) -> TcpEndpoint): CassandraExposedConfiguration {
    require(hasSingleNode()) {
      "Cassandra chaos requires exactly one node and no advertised peers; a bootstrap proxy cannot isolate a cluster"
    }
    val endpoint = proxy(TcpEndpoint(direct.host, direct.port))
    return direct.copy(host = endpoint.host, port = endpoint.port)
  }
}
