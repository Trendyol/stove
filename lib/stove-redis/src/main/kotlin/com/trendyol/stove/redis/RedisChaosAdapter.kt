package com.trendyol.stove.redis

import com.trendyol.stove.chaos.ChaosEndpointAdapter
import com.trendyol.stove.chaos.TcpEndpoint
import java.net.URI

internal object RedisChaosAdapter : ChaosEndpointAdapter<RedisExposedConfiguration> {
  override fun endpoint(configuration: RedisExposedConfiguration): TcpEndpoint {
    validatedUri(configuration)
    return TcpEndpoint(configuration.host, configuration.port)
  }

  override fun applicationConfiguration(direct: RedisExposedConfiguration, endpoint: TcpEndpoint): RedisExposedConfiguration =
    direct.copy(
      host = endpoint.host,
      port = endpoint.port,
      redisUri = validatedUri(direct).routedThrough(endpoint)
    )

  private fun URI.routedThrough(endpoint: TcpEndpoint): String {
    val credentials = rawUserInfo?.let { "$it@" }.orEmpty()
    val query = rawQuery?.let { "?$it" }.orEmpty()
    val fragment = rawFragment?.let { "#$it" }.orEmpty()
    return "$scheme://$credentials${endpoint.authority}${rawPath.orEmpty()}$query$fragment"
  }

  private fun validatedUri(configuration: RedisExposedConfiguration): URI {
    val uri = URI(configuration.redisUri)
    require(uri.scheme in setOf("redis", "rediss") && uri.host != null) {
      "Chaos routing requires a single-server redis:// or rediss:// URI"
    }
    val host = uri.host.removeSurrounding("[", "]")
    val port = if (uri.port == -1) 6379 else uri.port
    require(host.equals(configuration.host.removeSurrounding("[", "]"), ignoreCase = true) && port == configuration.port) {
      "Chaos routing requires Redis URI host and port to match the exposed configuration"
    }
    return uri
  }
}
