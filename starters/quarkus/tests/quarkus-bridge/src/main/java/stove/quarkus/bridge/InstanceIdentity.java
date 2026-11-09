package stove.quarkus.bridge;

import jakarta.enterprise.context.ApplicationScoped;
import java.util.UUID;

@ApplicationScoped
public class InstanceIdentity {
  private final String id = UUID.randomUUID().toString();

  public String id() {
    return id;
  }
}
