package com.trendyol.stove.examples.java.spring.application.product.command;

import com.trendyol.stove.examples.domain.product.Product;
import com.trendyol.stove.examples.java.spring.application.external.category.CategoryHttpApi;
import com.trendyol.stove.examples.java.spring.domain.ProductRepository;
import com.trendyol.stove.recipes.shared.application.BusinessException;
import com.trendyol.stove.recipes.shared.application.category.CategoryApiResponse;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Component
public class ProductApplicationService {
  private final ProductRepository productRepository;
  private final CategoryHttpApi categoryHttpApi;

  public ProductApplicationService(
      ProductRepository productRepository, CategoryHttpApi categoryHttpApi) {
    this.productRepository = productRepository;
    this.categoryHttpApi = categoryHttpApi;
  }

  public Mono<Void> create(String name, double price, int categoryId) throws BusinessException {
    return categoryHttpApi
        .getCategoryById(categoryId)
        .filter(CategoryApiResponse::isActive)
        .switchIfEmpty(Mono.error(new BusinessException("Category is not active")))
        .flatMap(
            categoryApiResponse -> {
              var product = Product.create(name, price, categoryApiResponse.id());
              return productRepository.save(product);
            });
  }
}
