package com.trendyol.stove.recipes.quarkus;

import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

@Path("/hello")
public class GreetingResource {

    @Inject
    HelloService helloService;

    // Inject all GreetingService implementations to ensure they're registered
    @Inject
    Instance<GreetingService> greetingServices;

    // Inject repository to prevent dead code elimination
    @Inject
    ItemRepository itemRepository;

    @GET
    @Produces(MediaType.TEXT_PLAIN)
    public String hello() {
        return helloService.hello();
    }

    @GET
    @Path("/greetings")
    @Produces(MediaType.TEXT_PLAIN)
    public String greetings() {
        StringBuilder sb = new StringBuilder();
        for (GreetingService gs : greetingServices) {
            sb.append(gs.getLanguage()).append(": ").append(gs.greet("World")).append("\n");
        }
        return sb.toString();
    }
}
