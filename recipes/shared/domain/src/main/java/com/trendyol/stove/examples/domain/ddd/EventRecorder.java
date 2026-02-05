package com.trendyol.stove.examples.domain.ddd;

import java.util.ArrayList;
import java.util.List;

public class EventRecorder {
    private final List<DomainEvent> events;

    public EventRecorder() {
        this.events = new ArrayList<>();
    }

    public void record(DomainEvent event) {
        events.add(event);
    }

    public List<DomainEvent> getRecords() {
        return events;
    }

    public void removeAll() {
        events.clear();
    }
}
