package com.trendyol.stove.kafka.tests

import com.trendyol.stove.kafka.TopicSuffixes
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class TopicSuffixesTests :
  FunSpec({

    test("default error suffixes should match .error and .DLT") {
      val suffixes = TopicSuffixes()

      suffixes.isErrorTopic("my-topic.error") shouldBe true
      suffixes.isErrorTopic("my-topic.DLT") shouldBe true
      suffixes.isErrorTopic("my-topic") shouldBe false
      suffixes.isErrorTopic("my-topic.retry") shouldBe false
    }

    test("default retry suffixes should match .retry") {
      val suffixes = TopicSuffixes()

      suffixes.isRetryTopic("my-topic.retry") shouldBe true
      suffixes.isRetryTopic("my-topic") shouldBe false
      suffixes.isRetryTopic("my-topic.error") shouldBe false
    }

    test("error topic matching should be case-insensitive") {
      val suffixes = TopicSuffixes()

      suffixes.isErrorTopic("my-topic.ERROR") shouldBe true
      suffixes.isErrorTopic("my-topic.Error") shouldBe true
      suffixes.isErrorTopic("my-topic.dlt") shouldBe true
    }

    test("retry topic matching should be case-insensitive") {
      val suffixes = TopicSuffixes()

      suffixes.isRetryTopic("my-topic.RETRY") shouldBe true
      suffixes.isRetryTopic("my-topic.Retry") shouldBe true
    }

    test("custom suffixes should be used for matching") {
      val suffixes = TopicSuffixes(
        error = listOf(".dead-letter", ".failed"),
        retry = listOf(".retry-1", ".retry-2")
      )

      suffixes.isErrorTopic("my-topic.dead-letter") shouldBe true
      suffixes.isErrorTopic("my-topic.failed") shouldBe true
      suffixes.isErrorTopic("my-topic.error") shouldBe false

      suffixes.isRetryTopic("my-topic.retry-1") shouldBe true
      suffixes.isRetryTopic("my-topic.retry-2") shouldBe true
      suffixes.isRetryTopic("my-topic.retry") shouldBe false
    }

    test("empty suffixes should never match") {
      val suffixes = TopicSuffixes(error = emptyList(), retry = emptyList())

      suffixes.isErrorTopic("my-topic.error") shouldBe false
      suffixes.isRetryTopic("my-topic.retry") shouldBe false
    }
  })
