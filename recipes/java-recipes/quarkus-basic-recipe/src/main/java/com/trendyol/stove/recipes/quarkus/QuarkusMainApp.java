package com.trendyol.stove.recipes.quarkus;

import io.quarkus.runtime.Quarkus;
import io.quarkus.runtime.QuarkusApplication;
import io.quarkus.runtime.annotations.QuarkusMain;
import jakarta.enterprise.inject.spi.BeanManager;
import jakarta.inject.Inject;

@QuarkusMain
public class QuarkusMainApp {
  public static void main(String[] args) throws InterruptedException {
    var t = new Thread(() -> Quarkus.run(QuarkusApp.class, args));
    t.setContextClassLoader(Thread.currentThread().getContextClassLoader());
    t.start();
    t.join();
  }

  public static class QuarkusApp implements QuarkusApplication {

    @Inject BeanManager beanManager;

    @Override
    public int run(String... args) {
      StoveQuarkusBridge.set(beanManager, Thread.currentThread().getContextClassLoader());
      Quarkus.waitForExit();
      return 0;
    }
  }
}
