package com.trendyol.stove.examples.java.spring.infra.boilerplate.couchbase;

import com.couchbase.client.java.ClusterOptions;
import com.couchbase.client.java.ReactiveBucket;
import com.couchbase.client.java.ReactiveCluster;
import com.couchbase.client.java.codec.JacksonJsonSerializer;
import com.couchbase.client.java.codec.JsonTranscoder;
import com.couchbase.client.java.env.ClusterEnvironment;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.Arrays;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class CouchbaseBeanConfiguration {

  @Bean(destroyMethod = "disconnect")
  public ReactiveCluster reactiveCluster(
      CouchbaseConfiguration couchbaseConfiguration, ClusterEnvironment clusterEnvironment) {
    String connectionString = String.join(",", Arrays.asList(couchbaseConfiguration.getHosts()));
    return ReactiveCluster.connect(
        connectionString,
        ClusterOptions.clusterOptions(
                couchbaseConfiguration.getUsername(), couchbaseConfiguration.getPassword())
            .environment(clusterEnvironment));
  }

  @Bean
  public ReactiveBucket reactiveBucket(
      ReactiveCluster reactiveCluster, CouchbaseConfiguration couchbaseConfiguration) {
    return reactiveCluster.bucket(couchbaseConfiguration.getBucket());
  }

  @Bean
  public ClusterEnvironment clusterEnvironment(
      ObjectMapper objectMapper, CouchbaseConfiguration couchbaseConfiguration) {
    return ClusterEnvironment.builder()
        .jsonSerializer(JacksonJsonSerializer.create(objectMapper))
        .timeoutConfig(
            t -> {
              t.connectTimeout(Duration.ofSeconds(couchbaseConfiguration.getTimeout()));
              t.kvTimeout(Duration.ofSeconds(couchbaseConfiguration.getTimeout()));
              t.queryTimeout(Duration.ofSeconds(couchbaseConfiguration.getTimeout()));
              t.viewTimeout(Duration.ofSeconds(couchbaseConfiguration.getTimeout()));
              t.searchTimeout(Duration.ofSeconds(couchbaseConfiguration.getTimeout()));
              t.analyticsTimeout(Duration.ofSeconds(couchbaseConfiguration.getTimeout()));
              t.managementTimeout(Duration.ofSeconds(couchbaseConfiguration.getTimeout()));
            })
        .compressionConfig(c -> c.enable(true).build())
        .transcoder(JsonTranscoder.create(JacksonJsonSerializer.create(objectMapper)))
        .build();
  }
}
