package com.trendyol.stove.recipes.micronaut.infra.api;

import io.micronaut.serde.annotation.Serdeable;

@Serdeable
public record CreateProductRequest(String id, String name, long supplierId) {}
