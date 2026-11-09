package stove.quarkus.bridge;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.concurrent.atomic.AtomicInteger;

@ApplicationScoped
public class CounterService {
  private final AtomicInteger value = new AtomicInteger();
  private final InstanceIdentity identity;

  @Inject
  public CounterService(InstanceIdentity identity) {
    this.identity = identity;
  }

  public CounterSnapshot increment() {
    return new CounterSnapshot(value.incrementAndGet(), identity.id());
  }

  public CounterSnapshot snapshot() {
    return new CounterSnapshot(value.get(), identity.id());
  }
}
