package com.trendyol.stove.recipes.quarkus;

import io.quarkus.runtime.Quarkus;
import io.quarkus.runtime.QuarkusApplication;
import io.quarkus.runtime.annotations.QuarkusMain;

@QuarkusMain
public class QuarkusMainApp {
  public static void main(String[] args) {
    Quarkus.run(QuarkusApp.class, args);
  }

  public static class QuarkusApp implements QuarkusApplication {

    @Override
    public int run(String... args) {
      Quarkus.waitForExit();
      return 0;
    }
  }
}
