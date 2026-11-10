package com.trendyol.stove.recipes.quarkus;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/** An operations bean used only through Bridge in this recipe. */
@ApplicationScoped
public class InventorySummary {
  @Inject
  ItemRepository items;

  public boolean contains(String id) {
    return items.getItemById(id) != null;
  }
}
