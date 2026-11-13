package com.trendyol.stove.elasticsearch

import com.trendyol.stove.chaos.ChaosEndpointAdapter
import com.trendyol.stove.chaos.TcpEndpoint

internal val ElasticsearchChaosAdapter = ChaosEndpointAdapter<ElasticSearchExposedConfiguration> { direct, proxy ->
  val endpoint = proxy(TcpEndpoint(direct.host, direct.port))
  direct.copy(host = endpoint.host, port = endpoint.port)
}
