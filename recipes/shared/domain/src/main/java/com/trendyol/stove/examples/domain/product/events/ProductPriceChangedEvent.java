package com.trendyol.stove.examples.domain.product.events;

import com.trendyol.stove.examples.domain.ddd.DomainEvent;

import lombok.NoArgsConstructor;

@NoArgsConstructor(force = true)
public class ProductPriceChangedEvent extends DomainEvent {
    public final double newPrice;

    public ProductPriceChangedEvent(double newPrice) {
        this.newPrice = newPrice;
    }
}
