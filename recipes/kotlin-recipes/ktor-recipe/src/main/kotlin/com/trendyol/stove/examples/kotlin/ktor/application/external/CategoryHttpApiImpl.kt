package com.trendyol.stove.examples.kotlin.ktor.application.external

import com.trendyol.stove.recipes.shared.application.category.*
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.http.*

class CategoryHttpApiImpl(
  private val httpClient: HttpClient,
  private val categoryApiConfiguration: CategoryApiConfiguration
) : CategoryHttpApi {
  override suspend fun getCategory(id: Int): CategoryApiResponse {
    return httpClient
      .get("${categoryApiConfiguration.url}/categories/$id") {
        accept(ContentType.Application.Json)
      }
      .body()
  }
}
