package com.trendyol.stove.recipes.quarkus;

/** An item shared by the HTTP application and Bridge tests. */
public class Item {
  private final String id;
  private final String name;

  public Item(String id, String name) {
    this.id = id;
    this.name = name;
  }

  public String getId() {
    return id;
  }

  public String getName() {
    return name;
  }

  @Override
  public String toString() {
    return "Item{id='" + id + "', name='" + name + "'}";
  }
}
