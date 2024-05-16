package com.trendyol.stove.examples.kotlin.ktor.application.external

import com.trendyol.stove.recipes.shared.application.category.CategoryApiResponse
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.http.*
import kotlin.time.Duration.Companion.seconds

class CategoryHttpApiImpl(
  private val httpClient: HttpClient,
  private val categoryApiConfiguration: CategoryApiConfiguration
) : CategoryHttpApi {
  override suspend fun getCategory(id: Int): CategoryApiResponse {
    return httpClient
      .get("${categoryApiConfiguration.url}/categories/$id") {
        accept(ContentType.Application.Json)
        timeout {
          requestTimeoutMillis = categoryApiConfiguration.timeout.seconds.inWholeMilliseconds
          connectTimeoutMillis = categoryApiConfiguration.timeout.seconds.inWholeMilliseconds
          socketTimeoutMillis = categoryApiConfiguration.timeout.seconds.inWholeMilliseconds
        }
      }
      .body()
  }
}
