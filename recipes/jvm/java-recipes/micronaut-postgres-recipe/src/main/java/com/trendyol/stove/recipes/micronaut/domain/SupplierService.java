package com.trendyol.stove.recipes.micronaut.domain;

import reactor.core.publisher.Mono;

public interface SupplierService {
  Mono<SupplierPermission> getSupplierPermission(long supplierId);
}
