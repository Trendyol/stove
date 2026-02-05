package com.trendyol.stove.examples.domain.ddd;

public interface EventPublisher {
    <TId> void publishFor(AggregateRoot<TId> aggregateRoot);
}
