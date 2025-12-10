package com.trendyol.stove.recipes.quarkus;

/**
 * Simple item class to demonstrate classloader limitations.
 */
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
