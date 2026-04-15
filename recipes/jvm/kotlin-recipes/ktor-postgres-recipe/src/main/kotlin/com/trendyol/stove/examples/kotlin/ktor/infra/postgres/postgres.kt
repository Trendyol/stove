package com.trendyol.stove.examples.kotlin.ktor.infra.postgres

import org.flywaydb.core.Flyway
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.koin.core.KoinApplication
import org.koin.dsl.module
import org.postgresql.ds.PGSimpleDataSource

data class R2dbcProperties(
  val url: String,
  val username: String,
  val password: String,
  val flyway: Flyway,
  val driverClassName: String = "postgresql"
) {
  fun jdbcUrl(): String = url.replace("r2dbc:", "jdbc:")

  data class Flyway(
    val enabled: Boolean,
    val logLevel: String = "INFO",
    val table: String = "flyway_schema_history",
    val locations: String = "classpath:db/migration"
  )
}

fun KoinApplication.configurePostgres() {
  modules(postgresModule())
}

private fun postgresModule() = module {
  single(createdAtStart = true) { exposedDatabase(get()) }
  single { flyway(get()) }
}

fun exposedDatabase(
  postgresDbConfiguration: R2dbcProperties
): R2dbcDatabase = R2dbcDatabase.connect(
  url = postgresDbConfiguration.url,
  driver = postgresDbConfiguration.driverClassName,
  user = postgresDbConfiguration.username,
  password = postgresDbConfiguration.password
)

fun flyway(
  r2dbcProperties: R2dbcProperties
): Flyway {
  val dataSource = PGSimpleDataSource().apply {
    setURL(r2dbcProperties.jdbcUrl())
    user = r2dbcProperties.username
    password = r2dbcProperties.password
  }
  return Flyway
    .configure()
    .dataSource(dataSource)
    .locations(r2dbcProperties.flyway.locations)
    .baselineOnMigrate(true)
    .baselineVersion("0")
    .loggers("slf4j")
    .load()
}
