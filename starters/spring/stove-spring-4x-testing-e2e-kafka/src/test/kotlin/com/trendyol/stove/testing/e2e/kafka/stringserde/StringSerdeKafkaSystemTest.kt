package com.trendyol.stove.testing.e2e.kafka.stringserde

import arrow.core.some
import com.trendyol.stove.testing.e2e.*
import com.trendyol.stove.testing.e2e.kafka.*
import com.trendyol.stove.testing.e2e.serialization.StoveSerde
import com.trendyol.stove.testing.e2e.system.TestSystem
import com.trendyol.stove.testing.e2e.system.TestSystem.Companion.validate
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe
import org.apache.kafka.clients.admin.NewTopic
import kotlin.random.Random

class StringSerdeKafkaSystemTests :
  ShouldSpec({
    beforeSpec {
      TestSystem()
        .with {
          kafka {
            KafkaSystemOptions(
              configureExposedConfiguration = {
                listOf(
                  "kafka.bootstrapServers=${it.bootstrapServers}",
                  "kafka.groupId=test-group",
                  "kafka.offset=earliest"
                )
              },
              containerOptions = KafkaContainerOptions(tag = "7.8.1")
            )
          }
          springBoot(
            runner = { params ->
              KafkaTestSpringBotApplicationForStringSerde.run(params) {
                addInitializers(
                  stoveSpringRegistrar {
                    registerBean<TestSystemKafkaInterceptor<*, *>>(primary = true)
                    registerBean { StoveSerde.jackson.anyByteArraySerde() }
                  }
                )
              }
            },
            withParameters = listOf(
              "spring.lifecycle.timeout-per-shutdown-phase=0s"
            )
          )
        }.run()
    }

    afterSpec {
      TestSystem.stop()
    }

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
            val message = "this message is coming from ${testCase.descriptor.id.value} and testName is ${testCase.name.name}"
            val headers = mapOf("x-user-id" to userId)
            publish("topic-failed", message, headers = headers)
            shouldBePublished<Any> {
              actual == message && this.metadata.headers["x-user-id"] == userId && this.metadata.topic == "topic-failed"
            }
            shouldBeFailed<Any> {
              actual == message && this.metadata.headers["x-user-id"] == userId && this.metadata.topic == "topic-failed" && reason is StoveBusinessException
            }

            shouldBePublished<Any> {
              actual == message && this.metadata.headers["x-user-id"] == userId && this.metadata.topic == "topic-failed-dlt"
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
