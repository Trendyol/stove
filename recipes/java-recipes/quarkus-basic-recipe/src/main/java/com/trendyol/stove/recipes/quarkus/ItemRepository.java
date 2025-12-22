package com.trendyol.stove.recipes.quarkus;

import java.util.List;

/** Simple repository interface for testing cross-classloader interactions. */
public interface ItemRepository {
  void add(String id, String name);

  void addItem(Item item); // Takes complex object - will fail across classloaders!

  String getById(String id);

  Item getItemById(String id); // Returns complex object

  List<String> getAllIds();

  void clear();

  int count();
}
