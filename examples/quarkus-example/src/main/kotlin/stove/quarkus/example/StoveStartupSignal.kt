package stove.quarkus.example

import io.quarkus.runtime.ShutdownEvent
import io.quarkus.runtime.StartupEvent
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Observes

@Suppress("unused")
@ApplicationScoped
class StoveStartupSignal {
  fun onStart(
    @Observes event: StartupEvent
  ) {
    System.setProperty(READY_PROPERTY, "true")
  }

  fun onStop(
    @Observes event: ShutdownEvent
  ) {
    System.clearProperty(READY_PROPERTY)
  }

  companion object {
    const val READY_PROPERTY: String = "stove.quarkus.ready"
  }
}
