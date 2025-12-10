package com.trendyol.stove.recipes.quarkus;

/**
 * Interface for HelloService - using interfaces is a CDI best practice
 * and enables type-safe testing through dynamic proxies.
 */
public interface HelloService {
  String hello();
}
