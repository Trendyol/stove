package com.trendyol.stove.examples.kotlin.ktor.infra.boilerplate.serialization

import com.fasterxml.jackson.databind.*
import com.fasterxml.jackson.databind.json.JsonMapper
import io.ktor.http.*
import io.ktor.serialization.jackson.*
import io.ktor.server.application.*
import io.ktor.server.plugins.contentnegotiation.*
import org.koin.core.KoinApplication
import org.koin.dsl.module
import org.koin.ktor.ext.inject

object JacksonConfiguration {
  val default: ObjectMapper = JsonMapper
    .builder()
    .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
    .disable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
    .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
    .disable(DeserializationFeature.FAIL_ON_IGNORED_PROPERTIES)
    .disable(DeserializationFeature.FAIL_ON_INVALID_SUBTYPE)
    .findAndAddModules()
    .build()
    .findAndRegisterModules()
}

fun KoinApplication.configureJackson() {
  modules(module { single { JacksonConfiguration.default } })
}

fun Application.configureContentNegotiation() {
  val mapper: ObjectMapper by inject()
  install(ContentNegotiation) {
    register(ContentType.Application.Json, JacksonConverter(mapper))
  }
}
