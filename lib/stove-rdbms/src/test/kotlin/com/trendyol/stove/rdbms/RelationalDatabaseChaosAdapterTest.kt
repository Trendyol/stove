package com.trendyol.stove.rdbms

import com.trendyol.stove.chaos.TcpEndpoint
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class RelationalDatabaseChaosAdapterTest : FunSpec({
  test("routes JDBC authorities while preserving database, credentials and driver settings") {
    listOf(
      "jdbc:postgresql://db:5432/orders?sslmode=require&ApplicationName=a%20b" to 5432,
      "jdbc:mysql://db:3306/orders?useSSL=true&serverTimezone=UTC" to 3306,
      "jdbc:sqlserver://db:1433;databaseName=orders;encrypt=true;password={a;b}" to 1433
    ).forEach { (url, port) ->
      val direct = RelationalDatabaseExposedConfiguration(url, "db", port, "secret", "user")
      val routed = RelationalDatabaseChaosAdapter.applicationConfiguration(direct, TcpEndpoint("127.0.0.1", 12345))
      routed shouldBe direct.copy(
        jdbcUrl = url.replace("db:$port", "127.0.0.1:12345"),
        host = "127.0.0.1",
        port = 12345
      )
      RelationalDatabaseChaosAdapter.endpoint(direct) shouldBe TcpEndpoint("db", port)
      direct.jdbcUrl shouldBe url
    }
  }

  test("supports default ports and IPv6 without re-encoding URL contents") {
    val direct = RelationalDatabaseExposedConfiguration("jdbc:postgresql://[::1]/orders?options=a%3Db", "::1", 5432, "p", "u")
    RelationalDatabaseChaosAdapter.applicationConfiguration(direct, TcpEndpoint("::1", 12345)).jdbcUrl shouldBe
      "jdbc:postgresql://[::1]:12345/orders?options=a%3Db"
  }

  test("rejects routes that could bypass the single managed endpoint") {
    listOf(
      "jdbc:postgresql://db:5432,backup:5432/orders",
      "jdbc:mysql:loadbalance://db:5432/orders",
      "jdbc:sqlserver://db\\instance;databaseName=orders",
      "jdbc:sqlserver://db:5432;failoverPartner=backup",
      "jdbc:mysql://db:5432/orders?socketFactory=CustomSocketFactory",
      "jdbc:postgresql://other:5432/orders",
      "jdbc:postgresql://db:6543/orders",
      "jdbc:postgresql://db:999999999999/orders"
    ).forEach { url ->
      val direct = RelationalDatabaseExposedConfiguration(url, "db", 5432, "p", "u")
      shouldThrow<IllegalArgumentException> { RelationalDatabaseChaosAdapter.endpoint(direct) }
    }
  }
})
