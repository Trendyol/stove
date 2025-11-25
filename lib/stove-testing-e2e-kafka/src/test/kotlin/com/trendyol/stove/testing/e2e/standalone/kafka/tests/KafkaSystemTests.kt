package com.trendyol.stove.testing.e2e.standalone.kafka.tests

import arrow.core.some
import com.trendyol.stove.testing.e2e.standalone.kafka.kafka
import com.trendyol.stove.testing.e2e.standalone.kafka.setup.example.DomainEvents.ProductCreated
import com.trendyol.stove.testing.e2e.standalone.kafka.setup.example.DomainEvents.ProductFailingCreated
import com.trendyol.stove.testing.e2e.system.TestSystem.Companion.validate
import io.github.nomisRev.kafka.createTopic
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldNotContainAll
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.*
import org.apache.kafka.clients.admin.NewTopic
import org.apache.kafka.clients.consumer.ConsumerRecord
import kotlin.random.Random
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class KafkaSystemTests :
  FunSpec({
    val randomString = { Random.nextInt(0, Int.MAX_VALUE).toString() }

    test("migration should create test topics") {
      validate {
        kafka {
          adminOperations {
            val topics = listTopics().names().get()
            // Verify migration-created topics exist
            topics.contains("migration-test-topic") shouldBe true
            topics.contains("migration-test-topic-2") shouldBe true

            // Verify partition count
            val topicDescription = describeTopics(listOf("migration-test-topic-2")).allTopicNames().get()
            topicDescription["migration-test-topic-2"]?.partitions()?.size shouldBe 2
          }
        }
      }
    }

    test("When publish then it should work") {
      validate {
        kafka {
          val key = randomString()
          val productId = "$key[productCreated]"
          publish("product", message = ProductCreated(productId), key = key.some())
          shouldBePublished<ProductCreated> {
            actual.productId == productId
          }

          peekPublishedMessages(topic = "product") {
            it.key == key
          }

          shouldBeConsumed<ProductCreated>(1.minutes) {
            actual.productId == productId
          }

          peekConsumedMessages(topic = "product") {
            it.key == key
          }
        }
      }
    }
    test("lots of messages") {
      validate {
        kafka {
          val messages = ProductCreated.randoms(100)
          messages.map { async { publish("product", it, key = randomString().some()) } }.awaitAll()
          messages.map { async { shouldBePublished<ProductCreated> { actual.productId == it.productId } } }.awaitAll()
          messages.map { async { shouldBeConsumed<ProductCreated>(1.minutes) { actual.productId == it.productId } } }.awaitAll()

          peekConsumedMessages(topic = "product") {
            it.offset == 100L
          }

          peekCommittedMessages(topic = "product") { record ->
            record.offset == 101L // next offset
          }
        }
      }
    }

    test("When publish to a failing consumer should end-up throwing exception") {
      validate {
        kafka {
          val string = randomString()
          val productId = "$string[productFailingCreated]"
          val key = string.some()
          publish("productFailing", ProductFailingCreated(productId), key = key)
          shouldBeRetried<ProductFailingCreated>(atLeastIn = 1.minutes, times = 3) {
            actual.productId == productId
          }

          shouldBePublished<ProductFailingCreated>(atLeastIn = 1.minutes) {
            this.metadata.topic == "productFailing.error"
          }

          peekPublishedMessages(topic = "productFailing.error") {
            it.key == string
          }
        }
      }
    }

    test("in-flight consumer should commit the message after consuming it successfully") {
      validate {
        kafka {
          val key = randomString()
          val productId = "$key[productCreated]"
          val topic = randomString()

          adminOperations {
            createTopic(NewTopic(topic, 1, 1))
          }

          publish(topic, message = ProductCreated(productId), key = key.some())
          shouldBePublished<ProductCreated> {
            actual.productId == productId
          }

          consumer<String, ProductCreated>(topic, readOnly = false) {
            println(it) // it should commit
          }

          shouldBeConsumed<ProductCreated> {
            actual.productId == productId
          }
        }
      }
    }

    test("in-flight consumer: same consumer group after consuming successfully should not consume the same message again") {
      validate {
        kafka {
          val key = randomString()
          val productId = "$key[productCreated]"
          val topic = randomString()

          adminOperations {
            createTopic(NewTopic(topic, 1, 1))
          }

          publish(topic, message = ProductCreated(productId), key = key.some())
          shouldBePublished<ProductCreated> {
            actual.productId == productId
          }

          val consumerGroup1 = randomString()
          consumer<String, ProductCreated>(topic, readOnly = true, autoOffsetReset = "earliest", groupId = consumerGroup1) {
            println(it)
          }

          delay(3.seconds)
          val consumedMessages = this.messageStore().consumedMessages().filter { it.topic == topic }
          val committedMessages = this.messageStore().committedMessages().filter { it.topic == topic }
          committedMessages.map { it.offset } shouldNotContainAll consumedMessages.map { it.offset }

          // and consumer can re-read
          val reReadMessages = mutableListOf<ConsumerRecord<*, *>>()
          consumer<String, ProductCreated>(topic, readOnly = true, autoOffsetReset = "earliest", groupId = consumerGroup1) {
            reReadMessages.add(it)
          }
          reReadMessages.size shouldBe consumedMessages.size

          // and consumer can commit
          consumer<String, ProductCreated>(topic, readOnly = false, autoOffsetReset = "earliest", groupId = consumerGroup1) {
            println(it)
          }

          val committedMessagesAfterCommit = mutableListOf<ConsumerRecord<*, *>>()
          consumer<String, ProductCreated>(topic, readOnly = true, autoOffsetReset = "earliest", groupId = consumerGroup1) {
            committedMessagesAfterCommit.add(it)
          }
          committedMessagesAfterCommit.size shouldBe 0
        }
      }
    }
  })
