package stove.quarkus.startup;

import io.quarkus.runtime.Quarkus;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.runtime.annotations.QuarkusMain;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;

@QuarkusMain
@ApplicationScoped
public class HeadlessApplication {
  public static void main(String[] args) {
    Quarkus.run(args);
  }

  void initialize(@Observes StartupEvent event) throws InterruptedException {
    Thread.sleep(500);
    if (Boolean.getBoolean("stove.fixture.fail-startup")) {
      throw new IllegalStateException("headless startup failed");
    }
  }
}
