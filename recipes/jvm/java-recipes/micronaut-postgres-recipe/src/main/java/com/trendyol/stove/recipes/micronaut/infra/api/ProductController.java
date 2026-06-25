package com.trendyol.stove.recipes.micronaut.infra.api;

import com.trendyol.stove.recipes.micronaut.application.ProductService;
import com.trendyol.stove.recipes.micronaut.domain.Product;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Post;
import io.micronaut.http.annotation.QueryValue;
import reactor.core.publisher.Mono;

@Controller("/products")
public class ProductController {

  private final ProductService productService;

  public ProductController(ProductService productService) {
    this.productService = productService;
  }

  @Get("/index")
  public String index(@QueryValue(defaultValue = "default") String keyword) {
    return "Hi from Stove framework with " + keyword;
  }

  @Post("/create")
  public Mono<Product> createProduct(@Body CreateProductRequest request) {
    return productService.createProduct(request.id(), request.name(), request.supplierId());
  }
}
