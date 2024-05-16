package com.trendyol.stove.examples.kotlin.ktor.infra.components.product

import com.trendyol.stove.examples.kotlin.ktor.application.product.command.registerProductCommandHandling
import com.trendyol.stove.examples.kotlin.ktor.domain.product.ProductRepository
import com.trendyol.stove.examples.kotlin.ktor.infra.boilerplate.kafka.ConsumerSupervisor
import com.trendyol.stove.examples.kotlin.ktor.infra.components.product.messaging.ProductAggregateRootEventsConsumer
import com.trendyol.stove.examples.kotlin.ktor.infra.components.product.persistency.MongoProductRepository
import org.koin.core.KoinApplication
import org.koin.dsl.*

fun KoinApplication.registerProductComponents() {
  modules(
    module {
      single { MongoProductRepository(get(), get(), get()) }.bind<ProductRepository>()
      single { ProductAggregateRootEventsConsumer(get(), get()) }.bind<ConsumerSupervisor<*, *>>()
    }
  )
  registerProductCommandHandling()
}
