package com.trendyol.stove.testing.e2e.kafka

import arrow.core.some
import com.trendyol.stove.testing.e2e.serialization.StoveSerde
import com.trendyol.stove.testing.e2e.system.TestSystem.Companion.validate
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe
import org.apache.kafka.clients.admin.NewTopic
import kotlin.random.Random

/**
 * Shared Kafka string serde tests that work across all Spring Boot versions.
 * Each version module should create their own test class that extends this.
 *
 * @param dltTopicSuffix Dead Letter Topic suffix - ".DLT" for Spring Boot 2.x, "-dlt" for 3.x/4.x
 */
abstract class StringSerdeKafkaSystemTests(
  private val dltTopicSuffix: String = ".DLT"
) : ShouldSpec({
    should("publish and consume") {
      validate {
        kafka {
          val userId = Random.nextInt().toString()
          val message =
            "this message is coming from ${testCase.descriptor.id.value} and testName is ${testCase.name.name}"
          val headers = mapOf("x-user-id" to userId)
          publish("topic", message, headers = headers)
          shouldBePublished<Any> {
            actual == message && this.metadata.headers["x-user-id"] == userId && this.metadata.topic == "topic"
          }
          shouldBeConsumed<Any> {
            actual == message && this.metadata.headers["x-user-id"] == userId && this.metadata.topic == "topic"
          }
        }
      }
    }

    should("publish and consume with failed consumer") {
      shouldThrowMaybe<StoveBusinessException> {
        validate {
          kafka {
            val userId = Random.nextInt().toString()
            val message =
              "this message is coming from ${testCase.descriptor.id.value} and testName is ${testCase.name.name}"
            val headers = mapOf("x-user-id" to userId)
            publish("topic-failed", message, headers = headers)
            shouldBePublished<Any> {
              actual == message && this.metadata.headers["x-user-id"] == userId && this.metadata.topic == "topic-failed"
            }
            shouldBeFailed<Any> {
              actual == message && this.metadata.headers["x-user-id"] == userId && this.metadata.topic == "topic-failed" &&
                reason is StoveBusinessException
            }

            shouldBePublished<Any> {
              actual == message && this.metadata.headers["x-user-id"] == userId && this.metadata.topic == "topic-failed$dltTopicSuffix"
            }
          }
        }
      }
    }

    should("admin operations") {
      validate {
        kafka {
          adminOperations {
            val topic = "topic"
            createTopics(listOf(NewTopic(topic, 1, 1)))
            listTopics().names().get().contains(topic) shouldBe true
            deleteTopics(listOf(topic))
            listTopics().names().get().contains(topic) shouldBe false
          }
        }
      }
    }

    should("publish with ser/de") {
      validate {
        kafka {
          val userId = Random.nextInt().toString()
          val message =
            "this message is coming from ${testCase.descriptor.id.value} and testName is ${testCase.name.name}"
          val headers = mapOf("x-user-id" to userId)
          publish("topic", message, serde = StoveSerde.jackson.anyJsonStringSerde().some(), headers = headers)
          shouldBePublished<String> {
            actual == message && this.metadata.headers["x-user-id"] == userId && this.metadata.topic == "topic"
          }
          shouldBeConsumed<String> {
            actual == message && this.metadata.headers["x-user-id"] == userId && this.metadata.topic == "topic"
          }
        }
      }
    }
  })
