package com.trendyol.stove.recipes.micronaut.infra.http;

import com.trendyol.stove.recipes.micronaut.domain.SupplierPermission;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.client.annotation.Client;
import reactor.core.publisher.Mono;

@Client(id = "lookup-api")
public interface SupplierHttpClient {

  @Get("/v2/suppliers/{supplierId}?storeFrontId=1")
  Mono<SupplierPermission> getSupplierPermission(long supplierId);
}
