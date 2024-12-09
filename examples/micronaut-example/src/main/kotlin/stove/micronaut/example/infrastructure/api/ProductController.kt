package stove.micronaut.example.infrastructure.api

import io.micronaut.http.annotation.*
import stove.micronaut.example.application.domain.Product
import stove.micronaut.example.application.services.ProductService
import stove.micronaut.example.infrastructure.api.model.request.CreateProductRequest

@Controller("/products")
class ProductController(
  private val productService: ProductService
) {
  @Get("/index")
  fun get(
    @QueryValue keyword: String = "default"
  ): String = "Hi from Stove framework with $keyword"

  @Post("/create")
  suspend fun createProduct(
    @Body request: CreateProductRequest
  ): Product = productService.createProduct(
    id = request.id,
    productName = request.name,
    supplierId = request.supplierId
  )
}
