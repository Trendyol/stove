package com.trendyol.stove.examples.java.spring.infra.boilerplate.kafka;

import java.util.Map;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "kafka")
public @Data class KafkaConfiguration {
  String bootstrapServers;
  String groupId;
  long requestTimeoutSeconds = 30;
  long heartbeatIntervalSeconds = 3;
  long sessionTimeoutSeconds = 10;
  boolean autoCreateTopics = true;
  String autoOffsetReset = "earliest";
  String[] interceptorClasses;
  Map<String, Topic> topics;

  public String flattenInterceptorClasses() {
    return String.join(",", interceptorClasses);
  }
}
