package com.trendyol.stove.examples.kotlin.ktor.infra.postgres

import io.ktor.server.application.*
import org.flywaydb.core.Flyway
import org.koin.ktor.ext.get

fun Application.configureFlyway() {
  this.monitor.subscribe(ApplicationStarted) {
    val logger = environment.log
    val options = get<R2dbcProperties>()
    if (options.flyway.enabled) {
      logger.info("Flyway enabled, starting migration...")
      val flyway = get<Flyway>()
      flyway.migrate()
    } else {
      logger.info("Flyway disabled, skipping migration...")
    }
  }
}
