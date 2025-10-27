package com.trendyol.stove.examples.kotlin.ktor.infra.boilerplate.kediatr

import com.trendyol.kediatr.*
import com.trendyol.kediatr.koin.KediatRKoin
import org.koin.core.KoinApplication
import org.koin.dsl.module

fun KoinApplication.registerKediatR() {
  modules(
    module {
      single { KediatRKoin.getMediator() }
      single { LoggingPipelineBehaviour() }
    }
  )
}

class LoggingPipelineBehaviour : PipelineBehavior {
  override suspend fun <TRequest : Message, TResponse> handle(
    request: TRequest,
    next: suspend (TRequest) -> TResponse
  ): TResponse {
    println("Handling request: $request")
    val response = next(request)
    println("Handled request: $request")
    return response
  }
}
