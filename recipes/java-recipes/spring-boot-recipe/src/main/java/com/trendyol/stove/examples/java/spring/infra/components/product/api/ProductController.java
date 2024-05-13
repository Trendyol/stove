package com.trendyol.stove.examples.java.spring.infra.components.product.api;

import com.trendyol.stove.examples.java.spring.application.product.command.ProductApplicationService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/products")
public class ProductController {
  private final ProductApplicationService productService;

  public ProductController(ProductApplicationService productService) {
    this.productService = productService;
  }

  @PostMapping
  public Mono<ResponseEntity<?>> createProduct(@RequestBody ProductCreateRequest request) {
    return productService
        .create(request.getName(), request.getPrice())
        .onErrorContinue((throwable, o) -> ResponseEntity.badRequest().build())
        .then(Mono.fromCallable(() -> ResponseEntity.ok().build()));
  }
}
