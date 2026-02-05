package com.trendyol.stove.examples.domain.product.events;

import com.trendyol.stove.examples.domain.ddd.DomainEvent;
import lombok.NoArgsConstructor;

@NoArgsConstructor(force = true)
public class ProductCreatedEvent extends DomainEvent {
  public final String name;
  public final double price;
  public final int categoryId;

  public ProductCreatedEvent(String name, double price, int categoryId) {
    this.name = name;
    this.price = price;
    this.categoryId = categoryId;
  }
}
