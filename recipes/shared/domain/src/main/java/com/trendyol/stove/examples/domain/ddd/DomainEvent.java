package com.trendyol.stove.examples.domain.ddd;

import lombok.AccessLevel;
import lombok.Setter;

public abstract class DomainEvent {
    public final String type = this.getClass().getSimpleName();

    @Setter(AccessLevel.PROTECTED)
    private long version;

    public DomainEvent() {
        this.version = 0;
    }
}
