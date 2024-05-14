package com.trendyol.stove.examples.kotlin.ktor.infra.components.external

import com.trendyol.stove.examples.kotlin.ktor.application.external.CategoryHttpApiImpl
import org.koin.core.KoinApplication

fun KoinApplication.configureCategoryExternalApi() {
  modules(createCategoryExternalApi())
}

private fun createCategoryExternalApi() = org.koin.dsl.module {
  single { CategoryHttpApiImpl(get(), get()) }
}
