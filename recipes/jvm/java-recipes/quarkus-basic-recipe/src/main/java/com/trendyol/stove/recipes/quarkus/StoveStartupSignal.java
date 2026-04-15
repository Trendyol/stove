package com.trendyol.stove.recipes.quarkus;

import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;

@ApplicationScoped
public class StoveStartupSignal {

  public static final String READY_PROPERTY = "stove.quarkus.ready";

  void onStart(@Observes StartupEvent event) {
    System.setProperty(READY_PROPERTY, "true");
  }

  void onStop(@Observes ShutdownEvent event) {
    System.clearProperty(READY_PROPERTY);
  }
}
