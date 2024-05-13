package com.trendyol.stove.examples.java.spring.application.product.command;

import com.trendyol.stove.examples.domain.product.Product;
import com.trendyol.stove.examples.java.spring.domain.ProductRepository;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Component
public class ProductApplicationService {
  private final ProductRepository productRepository;

  public ProductApplicationService(ProductRepository productRepository) {
    this.productRepository = productRepository;
  }

  public Mono<Void> create(String name, double price) {
    var product = Product.create(name, price);
    return productRepository.save(product);
  }
}
