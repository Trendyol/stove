package com.trendyol.stove.elasticsearch

import com.trendyol.stove.chaos.ChaosEndpointAdapter
import com.trendyol.stove.chaos.TcpEndpoint

internal object ElasticsearchChaosAdapter : ChaosEndpointAdapter<ElasticSearchExposedConfiguration> {
  override fun endpoint(configuration: ElasticSearchExposedConfiguration): TcpEndpoint =
    TcpEndpoint(configuration.host, configuration.port)

  override fun applicationConfiguration(
    direct: ElasticSearchExposedConfiguration,
    endpoint: TcpEndpoint
  ): ElasticSearchExposedConfiguration = direct.copy(host = endpoint.host, port = endpoint.port)
}
