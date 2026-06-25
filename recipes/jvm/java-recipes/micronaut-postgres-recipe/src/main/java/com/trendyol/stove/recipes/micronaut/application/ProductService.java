package com.trendyol.stove.recipes.micronaut.application;

import com.trendyol.stove.recipes.micronaut.domain.Product;
import com.trendyol.stove.recipes.micronaut.domain.ProductRepository;
import com.trendyol.stove.recipes.micronaut.domain.SupplierService;
import jakarta.inject.Singleton;
import java.util.UUID;
import reactor.core.publisher.Mono;

@Singleton
public class ProductService {

  private final ProductRepository productRepository;
  private final SupplierService supplierService;

  public ProductService(ProductRepository productRepository, SupplierService supplierService) {
    this.productRepository = productRepository;
    this.supplierService = supplierService;
  }

  public Mono<Product> createProduct(String id, String productName, long supplierId) {
    String productId = id != null ? id : UUID.randomUUID().toString();
    return supplierService
        .getSupplierPermission(supplierId)
        .map(supplier ->
            Product.create(productId, productName, supplierId, supplier.isBlacklisted()))
        .flatMap(productRepository::save);
  }
}
