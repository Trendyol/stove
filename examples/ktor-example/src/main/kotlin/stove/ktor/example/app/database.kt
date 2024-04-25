package stove.ktor.example.app

import io.r2dbc.postgresql.*
import org.koin.dsl.module
import stove.ktor.example.CONNECT_TIMEOUT_SECONDS
import java.time.Duration

fun postgresql(args: Array<String>) = module {
  val map = args.associate { it.split("=")[0] to it.split("=")[1] }
  single {
    val builder = PostgresqlConnectionConfiguration.builder().apply {
      host(map["database.host"]!!)
      database(map["database.databaseName"]!!)
      port(map["database.port"]!!.toInt())
      password(map["database.password"]!!)
      username(map["database.username"]!!)
    }

    PostgresqlConnectionFactory(builder.connectTimeout(Duration.ofSeconds(CONNECT_TIMEOUT_SECONDS)).build())
  }
}
