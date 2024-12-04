package com.trendyol.stove.example.java.spring.e2e.setup

import com.couchbase.client.kotlin.codec.JacksonJsonSerializer
import com.trendyol.stove.example.java.spring.e2e.setup.migrations.CouchbaseMigration
import com.trendyol.stove.examples.java.spring.ExampleSpringBootApp
import com.trendyol.stove.examples.java.spring.infra.boilerplate.serialization.JacksonConfiguration
import com.trendyol.stove.examples.java.spring.infra.components.product.persistency.CollectionConstants
import com.trendyol.stove.testing.e2e.*
import com.trendyol.stove.testing.e2e.couchbase.*
import com.trendyol.stove.testing.e2e.http.*
import com.trendyol.stove.testing.e2e.serialization.StoveSerde
import com.trendyol.stove.testing.e2e.standalone.kafka.*
import com.trendyol.stove.testing.e2e.system.TestSystem
import com.trendyol.stove.testing.e2e.wiremock.*
import io.kotest.core.config.AbstractProjectConfig
import io.ktor.serialization.jackson.*
import org.springframework.kafka.support.serializer.JsonSerializer

class Stove : AbstractProjectConfig() {
  override suspend fun beforeProject() {
    TestSystem()
      .with {
        httpClient {
          HttpClientSystemOptions(
            baseUrl = "http://localhost:8080",
            contentConverter = JacksonConverter(JacksonConfiguration.defaultObjectMapper())
          )
        }

        bridge()
        wiremock {
          WireMockSystemOptions(
            port = 9090,
            serde = StoveSerde.jackson.anyByteArraySerde(JacksonConfiguration.defaultObjectMapper())
          )
        }
        couchbase {
          CouchbaseSystemOptions(
            defaultBucket = "stove-java-spring-boot",
            CouchbaseContainerOptions {
              withStartupAttempts(3)
              dockerImageName = "couchbase/server:7.2.5"
            },
            clusterSerDe = JacksonJsonSerializer(JacksonConfiguration.defaultObjectMapper()),
            configureExposedConfiguration = { cfg ->
              listOf(
                "couchbase.bucket=${CollectionConstants.BUCKET_NAME}",
                "couchbase.username=${cfg.username}",
                "couchbase.password=${cfg.password}",
                "couchbase.hosts=${cfg.hostsWithPort}",
                "couchbase.timeout=600"
              )
            }
          ).migrations {
            register<CouchbaseMigration>()
          }
        }

        kafka {
          KafkaSystemOptions(
            serde = StoveSerde.jackson.anyByteArraySerde(JacksonConfiguration.defaultObjectMapper()),
            valueSerializer = JsonSerializer(JacksonConfiguration.defaultObjectMapper()),
            containerOptions = KafkaContainerOptions {
              withStartupAttempts(3)
            },
            configureExposedConfiguration = {
              listOf(
                "kafka.bootstrap-servers=${it.bootstrapServers}",
                "kafka.interceptor-classes=${it.interceptorClass}"
              )
            }
          )
        }
        springBoot(
          runner = { parameters ->
            ExampleSpringBootApp.run(parameters) {
            }
          },
          withParameters = listOf()
        )
      }.run()
  }

  override suspend fun afterProject() {
    TestSystem.stop()
  }
}
