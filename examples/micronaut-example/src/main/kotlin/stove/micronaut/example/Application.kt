package stove.micronaut.example

import io.micronaut.context.ApplicationContext
import io.micronaut.runtime.EmbeddedApplication

fun main(args: Array<String>) {
  run(args)
}

fun run(
  args: Array<String>,
  init: ApplicationContext.() -> Unit = {}
): ApplicationContext {
  val context = ApplicationContext
    .builder()
    .args(*args)
    .build()
    .also(init)
    .start()

  context.findBean(EmbeddedApplication::class.java).ifPresent { app ->
    if (!app.isRunning) {
      app.start()
    }
  }

  return context
}
