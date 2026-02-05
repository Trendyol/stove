package com.trendyol.stove.examples.java.spring.application.external.category;

import com.trendyol.stove.recipes.shared.application.category.CategoryApiResponse;

import org.springframework.web.reactive.function.client.WebClient;

import reactor.core.publisher.Mono;

public class CategoryHttpApiImpl implements CategoryHttpApi {
    private final WebClient categoryWebClient;

    public CategoryHttpApiImpl(WebClient categoryWebClient) {
        this.categoryWebClient = categoryWebClient;
    }

    @Override
    public Mono<CategoryApiResponse> getCategoryById(int id) {
        return categoryWebClient
                .get()
                .uri("/categories/{id}", id)
                .retrieve()
                .bodyToMono(CategoryApiResponse.class);
    }
}
