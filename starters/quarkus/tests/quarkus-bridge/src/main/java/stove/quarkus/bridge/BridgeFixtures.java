package stove.quarkus.bridge;

import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

@ApplicationScoped
public class BridgeFixtures {
  @Produces
  @Singleton
  public List<String> labels() {
    return List.of("quarkus", "bridge");
  }

  @Dependent
  public static class DependentService {
    public static final AtomicInteger destroyed = new AtomicInteger();

    @PreDestroy
    void destroy() {
      destroyed.incrementAndGet();
    }
  }

  public interface AmbiguousService {}

  @ApplicationScoped
  public static class FirstService implements AmbiguousService {}

  @ApplicationScoped
  public static class SecondService implements AmbiguousService {}
}
