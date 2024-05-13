package com.trendyol.stove.examples.domain.ddd;

import java.util.function.Consumer;

public class Entity<TId, TAggregate extends AggregateRoot<?>> {
  private final TId id;
  private final EventRouter router;

  public Entity(TId id) {
    this.id = id;
    this.router = new EventRouter();
  }

  protected <TEvent extends DomainEvent> void register(
      Class<TEvent> event, Consumer<TEvent> eventAction) {
    router.register(event, eventAction);
  }

  public <TEvent> void route(TEvent event) {
    router.route(event);
  }
}
