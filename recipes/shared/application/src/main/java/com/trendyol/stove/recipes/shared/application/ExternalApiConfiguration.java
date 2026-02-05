package com.trendyol.stove.recipes.shared.application;

import lombok.Data;

@Data
public abstract class ExternalApiConfiguration {
    private String url;
    private int timeout;

    public ExternalApiConfiguration() {
        this("", 0);
    }

    public ExternalApiConfiguration(String url, int timeout) {
        this.url = url;
        this.timeout = timeout;
    }
}
