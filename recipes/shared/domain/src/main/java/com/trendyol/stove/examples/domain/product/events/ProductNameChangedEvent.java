package com.trendyol.stove.examples.domain.product.events;

import com.trendyol.stove.examples.domain.ddd.DomainEvent;
import lombok.NoArgsConstructor;

@NoArgsConstructor(force = true)
public class ProductNameChangedEvent extends DomainEvent {
  public final String newName;

  public ProductNameChangedEvent(String newName) {
    this.newName = newName;
  }
}
