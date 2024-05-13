package com.trendyol.stove.examples.domain.ddd;

import java.util.HashMap;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.function.Consumer;

public class EventRouter {
  private final Map<Class<?>, Consumer<?>> eventActions = new HashMap<>();

  public <TEvent extends DomainEvent> void register(
      Class<TEvent> eventClass, Consumer<TEvent> eventAction) {
    eventActions.put(eventClass, eventAction);
  }

  public <TEvent> void route(TEvent event) {
    if (!eventActions.containsKey(event.getClass())) {
      throw new NoSuchElementException("Handler not found for: " + event.getClass().getName());
    }
  }
}
