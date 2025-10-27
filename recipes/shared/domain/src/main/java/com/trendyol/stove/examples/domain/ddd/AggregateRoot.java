package com.trendyol.stove.examples.domain.ddd;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import lombok.Getter;

@SuppressWarnings("unchecked")
public abstract class AggregateRoot<TId> {
  private final EventRouter router;
  private final EventRecorder recorder;
  @Getter protected long version;
  @Getter private final TId id;

  protected AggregateRoot(TId id) {
    this.id = id;
    this.version = 0;
    this.router = new EventRouter();
    this.recorder = new EventRecorder();
  }

  protected <TEvent extends DomainEvent> void register(
      Class<TEvent> event, Consumer<TEvent> eventAction) {
    router.register(event, eventAction);
  }

  protected <TEvent extends DomainEvent> void applyEvent(TEvent event) {
    version++;
    event.setVersion(version);
    play(event);
    recorder.record(event);
  }

  protected <TEvent> void play(TEvent event) {
    router.route(event);
  }

  @JsonIgnore
  public String getIdAsString() {
    return id.toString();
  }

  @JsonIgnore
  public void clearDomainEvents() {
    recorder.removeAll();
  }

  @JsonIgnore
  public List<DomainEvent> domainEvents() {
    return recorder.getRecords();
  }

  @JsonIgnore
  public boolean hasChanges() {
    return !domainEvents().isEmpty();
  }

  @JsonIgnore
  public boolean isNew() {
    return version - domainEvents().size() == 0;
  }

  @JsonIgnore
  public String getAggregateName() {
    return this.getClass().getSimpleName().toLowerCase(Locale.ROOT);
  }

  @Override
  public boolean equals(Object other) {
    if (this == other) return true;
    if (getClass() != other.getClass()) return false;
    AggregateRoot<TId> otherAggregate = (AggregateRoot<TId>) other;
    return id.equals(otherAggregate.id);
  }

  @Override
  public int hashCode() {
    return id.hashCode();
  }
}
