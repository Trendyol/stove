package com.trendyol.stove.examples.java.spring.infra.boilerplate.couchbase;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "couchbase")
public @Data class CouchbaseConfiguration {
  String[] hosts;
  String bucket;
  String username;
  String password;
  long timeout = 30;
}
