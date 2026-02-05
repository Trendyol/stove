package com.trendyol.stove.recipes.quarkus;

import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class EnglishGreetingService implements GreetingService {
    @Override
    public String greet(String name) {
        return "Hello, " + name + "!";
    }

    @Override
    public String getLanguage() {
        return "English";
    }
}
