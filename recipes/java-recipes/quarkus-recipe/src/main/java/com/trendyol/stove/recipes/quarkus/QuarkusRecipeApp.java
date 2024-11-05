package com.trendyol.stove.recipes.quarkus;

import io.quarkus.arc.Arc;
import io.quarkus.dev.appstate.ApplicationStateNotification;
import io.quarkus.runtime.ApplicationLifecycleManager;
import io.quarkus.runtime.Quarkus;
import io.quarkus.runtime.QuarkusApplication;
import jakarta.enterprise.inject.spi.BeanManager;

import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

import static io.quarkus.dev.appstate.ApplicationStateNotification.State.STARTED;

class QuarkusApp implements QuarkusApplication {
  public static void main(String[] args) {
    var app = new QuarkusApp();
    try {
      app.run(args);
    } catch (Exception e) {
      Logger.getLogger(QuarkusApp.class.getName()).severe("Failed to start Quarkus: " + e.getMessage());
      System.exit(-1);
    }
  }

  @Override
  public int run(String... args) {
    QuarkusRecipeApp.run(args, "main");
    return 0;
  }
}


public class QuarkusRecipeApp {
  public static BeanManager run(String[] args, String whereDoesItComeFrom) {
    System.out.println("Where does it come from? " + whereDoesItComeFrom);

    CompletableFuture<Boolean> startupComplete = new CompletableFuture<>();
    ApplicationLifecycleManager.setAlreadyStartedCallback(started ->
      startupComplete.complete(true)
    );
    Thread quarkusThread = new Thread(() -> {
      Quarkus.run(args);
    }, "quarkus-main");
    quarkusThread.setDaemon(true);
    quarkusThread.start();

    try {
      while (ApplicationStateNotification.getState() != STARTED) {
        Thread.sleep(100);
      }

      while (ApplicationLifecycleManager.getCurrentApplication() == null) {
        Thread.sleep(1000);
      }

      return Arc.container().beanManager();

    } catch (Exception e) {
      throw new RuntimeException("Failed to start Quarkus", e);
    }
  }

  public static void stop() {
    try {
      Arc.shutdown();
    } finally {
      Quarkus.asyncExit();
    }
  }
}
