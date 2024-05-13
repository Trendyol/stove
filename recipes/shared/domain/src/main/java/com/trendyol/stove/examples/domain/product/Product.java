package com.trendyol.stove.examples.domain.product;

import com.trendyol.stove.examples.domain.ddd.AggregateRoot;
import com.trendyol.stove.examples.domain.product.events.ProductCreatedEvent;
import com.trendyol.stove.examples.domain.product.events.ProductNameChangedEvent;
import com.trendyol.stove.examples.domain.product.events.ProductPriceChangedEvent;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import lombok.Getter;

@Getter
public class Product extends AggregateRoot<String> {

  @SuppressWarnings("unused")
  private Product() {
    super(null);
  }

  private Product(String id, String name, double price, int categoryId) {
    super(id);
    this.name = name;
    this.price = price;
    this.categoryId = categoryId;
    register(ProductCreatedEvent.class, this::handle);
    register(ProductNameChangedEvent.class, this::handle);
    register(ProductPriceChangedEvent.class, this::handle);
  }

  private String name;
  private double price;
  private int categoryId;

  public void changePrice(double newPrice) {
    applyEvent(new ProductPriceChangedEvent(newPrice));
  }

  public void changeName(String newName) {
    applyEvent(new ProductNameChangedEvent(newName));
  }

  private void handle(ProductCreatedEvent event) {
    this.name = event.name;
    this.price = event.price;
  }

  private void handle(ProductPriceChangedEvent event) {
    this.price = event.newPrice;
  }

  private void handle(ProductNameChangedEvent event) {
    this.name = event.newName;
  }

  public static Product create(String name, double price, int categoryId) {
    var aggregate =
        new Product(
            UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8)).toString(),
            name,
            price,
            categoryId);
    aggregate.applyEvent(new ProductCreatedEvent(name, price, categoryId));
    return aggregate;
  }
}
