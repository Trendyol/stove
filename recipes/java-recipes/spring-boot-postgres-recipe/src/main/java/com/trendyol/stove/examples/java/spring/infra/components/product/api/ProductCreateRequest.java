package com.trendyol.stove.examples.java.spring.infra.components.product.api;

import lombok.Data;

@Data
public class ProductCreateRequest {
  String name;
  double price;
  int categoryId;

  public ProductCreateRequest(String name, double price, int categoryId) {
    this.name = name;
    this.price = price;
    this.categoryId = categoryId;
  }
}
