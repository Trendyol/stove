package com.trendyol.stove.recipes.quarkus;

import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

@Path("/items")
public class ItemResource {
  @Inject
  ItemRepository items;

  @GET
  @Produces(MediaType.TEXT_PLAIN)
  @Path("/{id}")
  public String get(@PathParam("id") String id) {
    Item item = items.getItemById(id);
    if (item == null) {
      throw new NotFoundException();
    }
    return item.getName();
  }

  @PUT
  @Path("/{id}")
  @Consumes(MediaType.TEXT_PLAIN)
  public void put(@PathParam("id") String id, String name) {
    items.addItem(new Item(id, name));
  }
}
