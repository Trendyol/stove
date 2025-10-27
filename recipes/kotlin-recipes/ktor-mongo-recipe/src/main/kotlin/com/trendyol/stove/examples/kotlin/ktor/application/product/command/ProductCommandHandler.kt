package com.trendyol.stove.examples.kotlin.ktor.application.product.command

import com.trendyol.kediatr.*
import com.trendyol.stove.examples.domain.product.Product
import com.trendyol.stove.examples.kotlin.ktor.application.external.CategoryHttpApi
import com.trendyol.stove.examples.kotlin.ktor.domain.product.ProductRepository
import com.trendyol.stove.recipes.shared.application.BusinessException
import io.github.oshai.kotlinlogging.KotlinLogging

data class CreateProductCommand(
  val name: String,
  val price: Double,
  val categoryId: Int
) : Request.Unit

class ProductCommandHandler(
  private val productRepository: ProductRepository,
  private val categoryHttpApi: CategoryHttpApi
) : RequestHandler.Unit<CreateProductCommand> {
  private val logger = KotlinLogging.logger { }

  override suspend fun handle(request: CreateProductCommand) {
    val category = categoryHttpApi.getCategory(request.categoryId)
    if (!category.isActive) {
      throw BusinessException("Category is not active")
    }

    productRepository.save(Product.create(request.name, request.price, request.categoryId))
    logger.info { "Product saved: $request" }
  }
}
