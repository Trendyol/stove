package com.trendyol.stove.rdbms

import com.trendyol.stove.chaos.ChaosEndpointAdapter
import com.trendyol.stove.chaos.TcpEndpoint

/** Application-only routing for single-server PostgreSQL, MySQL and SQL Server JDBC URLs. */
object RelationalDatabaseChaosAdapter : ChaosEndpointAdapter<RelationalDatabaseExposedConfiguration> {
  override fun endpoint(configuration: RelationalDatabaseExposedConfiguration): TcpEndpoint {
    validatedUrl(configuration)
    return TcpEndpoint(configuration.host, configuration.port)
  }

  override fun applicationConfiguration(
    direct: RelationalDatabaseExposedConfiguration,
    endpoint: TcpEndpoint
  ): RelationalDatabaseExposedConfiguration = direct.copy(
    host = endpoint.host,
    port = endpoint.port,
    jdbcUrl = validatedUrl(direct).routedThrough(endpoint)
  )

  private fun validatedUrl(configuration: RelationalDatabaseExposedConfiguration): JdbcUrl {
    val url = parseUrl(configuration.jdbcUrl)
    require(url.matches(configuration)) {
      "Chaos routing requires JDBC host and port to match the exposed configuration"
    }
    return url
  }

  private fun parseUrl(value: String): JdbcUrl {
    val match = requireNotNull(singleServer.matchEntire(value)) {
      "Chaos routing requires a single-server PostgreSQL, MySQL or SQL Server JDBC URL"
    }
    val (prefix, driver, host, port, suffix) = match.destructured
    require(!routingOverride.containsMatchIn(suffix)) {
      "Chaos routing does not support JDBC endpoint overrides, failover partners or custom socket factories"
    }
    return JdbcUrl(prefix, host.removeSurrounding("[", "]"), parsePort(port, driver), suffix)
  }

  private fun parsePort(value: String, driver: String): Int {
    if (value.isNotEmpty()) {
      return requireNotNull(value.toIntOrNull()) { "Chaos routing requires a valid JDBC port" }
    }
    return when (driver) {
      "postgresql" -> 5432
      "mysql" -> 3306
      else -> 1433
    }
  }

  private data class JdbcUrl(val prefix: String, val host: String, val port: Int, val suffix: String) {
    fun routedThrough(endpoint: TcpEndpoint): String = "$prefix${endpoint.authority}$suffix"

    fun matches(configuration: RelationalDatabaseExposedConfiguration): Boolean =
      host.equals(configuration.host.removeSurrounding("[", "]"), ignoreCase = true) && port == configuration.port
  }

  private val singleServer = Regex(
    "^(jdbc:(postgresql|mysql|sqlserver)://)(\\[[^\\]]+]|[A-Za-z0-9._-]+)(?::([0-9]+))?([/?;].*)?$"
  )
  private val routingOverride = Regex(
    "[?&;](?:socketFactory|socketFactoryClass|PGHOST|PGPORT|host|port|serverName|portNumber|instanceName|failoverPartner|namedPipePath)=",
    RegexOption.IGNORE_CASE
  )
}
