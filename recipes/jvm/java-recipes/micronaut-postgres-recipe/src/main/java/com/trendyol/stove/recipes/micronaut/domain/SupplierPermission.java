package com.trendyol.stove.recipes.micronaut.domain;

import io.micronaut.serde.annotation.Serdeable;

@Serdeable
public record SupplierPermission(long id, boolean isBlacklisted) {}
