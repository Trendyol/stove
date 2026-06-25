package com.trendyol.stove.recipes.micronaut;

import io.micronaut.context.ApplicationContext;
import io.micronaut.runtime.EmbeddedApplication;

/**
 * Application entry point for the Micronaut recipe.
 *
 * <p>{@link #run(String[])} returns the started {@link ApplicationContext} so that Stove can use it
 * as the runner result and bridge into application beans via {@code using<T>}.
 */
public final class Application {

  private Application() {}

  public static void main(String[] args) {
    run(args);
  }

  public static ApplicationContext run(String[] args) {
    ApplicationContext context = ApplicationContext.builder().args(args).build().start();

    context.findBean(EmbeddedApplication.class).ifPresent(app -> {
      if (!app.isRunning()) {
        app.start();
      }
    });

    return context;
  }
}
