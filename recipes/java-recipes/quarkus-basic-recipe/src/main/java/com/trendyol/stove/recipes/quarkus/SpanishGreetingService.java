package com.trendyol.stove.recipes.quarkus;

import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class SpanishGreetingService implements GreetingService {
  @Override
  public String greet(String name) {
    return "¡Hola, " + name + "!";
  }

  @Override
  public String getLanguage() {
    return "Spanish";
  }
}
