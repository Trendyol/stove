package stove.quarkus.bridge;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

@Path("/counter")
@Produces(MediaType.TEXT_PLAIN)
public class CounterResource {
  private final CounterService counter;

  public CounterResource(CounterService counter) {
    this.counter = counter;
  }

  @GET
  public String snapshot() {
    return format(counter.snapshot());
  }

  @POST
  public String increment() {
    return format(counter.increment());
  }

  private String format(CounterSnapshot snapshot) {
    return snapshot.value() + ":" + snapshot.instanceId();
  }
}
