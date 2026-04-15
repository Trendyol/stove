package com.trendyol.stove.recipes.quarkus;

import jakarta.enterprise.context.ApplicationScoped;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class InMemoryItemRepository implements ItemRepository {

  private final Map<String, String> items = new ConcurrentHashMap<>();

  @Override
  public void add(String id, String name) {
    items.put(id, name);
  }

  @Override
  public void addItem(Item item) {
    items.put(item.getId(), item.getName());
  }

  @Override
  public String getById(String id) {
    return items.get(id);
  }

  @Override
  public Item getItemById(String id) {
    String name = items.get(id);
    return name != null ? new Item(id, name) : null;
  }

  @Override
  public List<String> getAllIds() {
    return new ArrayList<>(items.keySet());
  }

  @Override
  public void clear() {
    items.clear();
  }

  @Override
  public int count() {
    return items.size();
  }
}
