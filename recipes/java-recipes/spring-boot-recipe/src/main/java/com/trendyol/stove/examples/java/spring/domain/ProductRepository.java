package com.trendyol.stove.examples.java.spring.domain;

import com.trendyol.stove.examples.domain.product.Product;
import reactor.core.publisher.Mono;

public interface ProductRepository {
  Mono<Void> save(Product product);
}
