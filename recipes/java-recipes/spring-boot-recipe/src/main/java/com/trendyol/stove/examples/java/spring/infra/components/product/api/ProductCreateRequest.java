package com.trendyol.stove.examples.java.spring.infra.components.product.api;

import lombok.Data;

@Data
public class ProductCreateRequest {
  String name;
  double price;

  public ProductCreateRequest(String name, double price) {
    this.name = name;
    this.price = price;
  }
}
