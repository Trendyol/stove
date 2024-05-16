package com.trendyol.stove.examples.kotlin.ktor.application.external

import com.trendyol.stove.recipes.shared.application.category.CategoryApiResponse

interface CategoryHttpApi {
  suspend fun getCategory(id: Int): CategoryApiResponse
}

data class CategoryApiConfiguration(
  val url: String,
  val timeout: Long
)
