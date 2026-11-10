package com.trendyol.stove.recipes.quarkus;

import java.util.List;

/** Application inventory accessed through HTTP and the CDI Bridge. */
public interface ItemRepository {
  void add(String id, String name);

  void addItem(Item item);

  String getById(String id);

  Item getItemById(String id);

  List<String> getAllIds();

  void clear();

  int count();
}
