package com.trendyol.stove.testing.e2e

import org.springframework.boot.ApplicationArguments
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import java.time.Instant

/**
 * Common test domain classes shared across Spring Boot version tests.
 */

fun interface GetUtcNow {
  companion object {
    val frozenTime: Instant = Instant.parse("2021-01-01T00:00:00Z")
  }

  operator fun invoke(): Instant
}

class SystemTimeGetUtcNow : GetUtcNow {
  override fun invoke(): Instant = GetUtcNow.frozenTime
}

class TestAppInitializers {
  var onEvent: Boolean = false
  var appReady: Boolean = false

  @EventListener(ApplicationReadyEvent::class)
  fun applicationReady() {
    onEvent = true
    appReady = true
  }
}

@Component
class ExampleService(
  private val getUtcNow: GetUtcNow
) {
  fun whatIsTheTime(): Instant = getUtcNow()
}

class ParameterCollectorOfSpringBoot(
  private val applicationArguments: ApplicationArguments
) {
  val parameters: List<String>
    get() = applicationArguments.sourceArgs.toList()
}
