package com.trendyol.stove.recipes.micronaut.domain;

import io.micronaut.data.annotation.Id;
import io.micronaut.data.annotation.MappedEntity;
import io.micronaut.serde.annotation.Serdeable;
import java.util.Date;

@Serdeable
@MappedEntity("products")
public record Product(
    @Id String id, String name, long supplierId, boolean isBlacklist, Date createdDate) {

  public static Product create(String id, String name, long supplierId, boolean isBlacklist) {
    return new Product(id, name, supplierId, isBlacklist, new Date());
  }
}
