package com.trendyol.stove.recipes.quarkus;

import jakarta.inject.Singleton;

@Singleton
public class HelloService {
  public String hello() {
    return "Hello from Quarkus Service";
  }
}
