package com.trendyol.stove.examples.kotlin.ktor.application.product.command

import org.koin.core.KoinApplication
import org.koin.dsl.module

fun KoinApplication.registerProductCommandHandling() {
  modules(
    module {
      single { ProductCommandHandler(get(), get()) }
    }
  )
}
