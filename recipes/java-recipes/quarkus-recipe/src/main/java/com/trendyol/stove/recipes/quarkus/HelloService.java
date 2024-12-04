package com.trendyol.stove.recipes.quarkus;

import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class HelloService {
  public String hello() {
    return "Hello from Quarkus Service";
  }
}
