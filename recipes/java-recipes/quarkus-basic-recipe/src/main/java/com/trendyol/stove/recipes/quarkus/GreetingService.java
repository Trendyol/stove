package com.trendyol.stove.recipes.quarkus;

/**
 * Interface for greeting services - demonstrates multiple implementations pattern.
 */
public interface GreetingService {
  String greet(String name);
  String getLanguage();
}

