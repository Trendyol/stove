package com.trendyol.stove.examples.java.spring.application.external.category;

import com.trendyol.stove.recipes.shared.application.category.CategoryApiConfiguration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "external-apis.category")
public class CategoryApiSpringConfiguration extends CategoryApiConfiguration {}
