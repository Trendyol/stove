package stove.ktor.example.app

import io.r2dbc.postgresql.*
import org.koin.core.context.GlobalContext.get
import org.koin.dsl.module
import stove.ktor.example.CONNECT_TIMEOUT_SECONDS
import java.time.Duration

fun postgresql() = module {
  single {
    val config = get<AppConfiguration>()
    val builder = PostgresqlConnectionConfiguration.builder().apply {
      host(config.database.host)
      database(config.database.name)
      port(config.database.port)
      password(config.database.password)
      username(config.database.username)
    }

    PostgresqlConnectionFactory(builder.connectTimeout(Duration.ofSeconds(CONNECT_TIMEOUT_SECONDS)).build())
  }
}
