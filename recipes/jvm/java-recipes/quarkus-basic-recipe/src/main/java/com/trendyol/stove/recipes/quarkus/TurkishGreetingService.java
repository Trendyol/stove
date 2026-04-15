package com.trendyol.stove.recipes.quarkus;

import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class TurkishGreetingService implements GreetingService {
  @Override
  public String greet(String name) {
    return "Merhaba, " + name + "!";
  }

  @Override
  public String getLanguage() {
    return "Turkish";
  }
}
