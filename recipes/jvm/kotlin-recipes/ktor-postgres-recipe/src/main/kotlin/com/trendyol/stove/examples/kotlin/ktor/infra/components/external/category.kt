package com.trendyol.stove.examples.kotlin.ktor.infra.components.external

import com.trendyol.stove.examples.kotlin.ktor.application.external.*
import org.koin.core.KoinApplication
import org.koin.dsl.bind

fun KoinApplication.registerCategoryExternalHttpApi() {
  modules(createCategoryExternalApi())
}

private fun createCategoryExternalApi() = org.koin.dsl.module {
  single { CategoryHttpApiImpl(get(), get()) }.bind<CategoryHttpApi>()
}
