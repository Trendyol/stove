package stove.quarkus.bridge;

import jakarta.enterprise.context.ApplicationScoped;

// Deliberately no injection points, observers, @Named or @Unremovable.
@ApplicationScoped
public class TestOnlyService {
  public String message() {
    return "retained for e2e";
  }
}
