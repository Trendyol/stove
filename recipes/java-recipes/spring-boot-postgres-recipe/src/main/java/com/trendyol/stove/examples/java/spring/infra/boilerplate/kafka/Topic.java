package com.trendyol.stove.examples.java.spring.infra.boilerplate.kafka;

import lombok.Data;

public @Data class Topic {
  String name;
  String retry;
  String deadLetter;
}
