package com.trendyol.stove.recipes.shared.application.category;

public record CategoryApiResponse(int id, String name, boolean isActive) {
  public CategoryApiResponse {
    if (name == null) {
      throw new IllegalArgumentException("Name cannot be null");
    }
  }
}
