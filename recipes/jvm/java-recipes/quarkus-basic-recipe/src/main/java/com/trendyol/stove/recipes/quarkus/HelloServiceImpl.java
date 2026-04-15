package com.trendyol.stove.recipes.quarkus;

import jakarta.inject.Singleton;

@Singleton
public class HelloServiceImpl implements HelloService {
  @Override
  public String hello() {
    return "Hello from Quarkus Service";
  }
}
