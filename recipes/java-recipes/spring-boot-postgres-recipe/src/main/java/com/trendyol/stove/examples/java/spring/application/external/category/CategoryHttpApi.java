package com.trendyol.stove.examples.java.spring.application.external.category;

import com.trendyol.stove.recipes.shared.application.BusinessException;
import com.trendyol.stove.recipes.shared.application.category.CategoryApiResponse;

import reactor.core.publisher.Mono;

public interface CategoryHttpApi {
    Mono<CategoryApiResponse> getCategoryById(int id) throws BusinessException;
}
