package com.trendyol.stove.recipes.micronaut.infra.http;

import com.trendyol.stove.recipes.micronaut.domain.SupplierPermission;
import com.trendyol.stove.recipes.micronaut.domain.SupplierService;
import jakarta.inject.Singleton;
import reactor.core.publisher.Mono;

@Singleton
public class SupplierHttpService implements SupplierService {

  private final SupplierHttpClient supplierHttpClient;

  public SupplierHttpService(SupplierHttpClient supplierHttpClient) {
    this.supplierHttpClient = supplierHttpClient;
  }

  @Override
  public Mono<SupplierPermission> getSupplierPermission(long supplierId) {
    return supplierHttpClient.getSupplierPermission(supplierId);
  }
}
