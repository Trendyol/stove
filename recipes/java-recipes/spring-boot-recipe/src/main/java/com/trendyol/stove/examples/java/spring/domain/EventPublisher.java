package com.trendyol.stove.examples.java.spring.domain;

import com.trendyol.stove.examples.domain.ddd.AggregateRoot;

public interface EventPublisher {
  <TId> void publishFor(AggregateRoot<TId> aggregateRoot);
}
