package com.trendyol.stove.examples.domain.testing.aggregateroot

import arrow.core.firstOrNone
import com.trendyol.stove.examples.domain.ddd.*
import io.kotest.assertions.*
import io.kotest.assertions.print.Printed
import io.kotest.common.mapError
import io.kotest.matchers.shouldBe

class AggregateRootAssertion<TId, TAggregateRoot : AggregateRoot<TId>>(
  val root: AggregateRoot<TId>
) {
  fun shouldHaveCount(expectedCount: Int) = runCatching { root.domainEvents().count().shouldBe(expectedCount) }
    .mapError {
      throw failure(
        expected = Expected(Printed(expectedCount.toString())),
        actual = Actual(Printed(root.domainEvents().count().toString())),
        prependMessage = "Expected Count but found:"
      )
    }

  inline fun <reified T : DomainEvent> shouldContain(act: T.() -> Unit) {
    shouldContain<T>()
    root.domainEvents().filter { it::class == T::class }.map { it as T }.forEach { act(it) }
  }

  inline fun <reified T : DomainEvent> shouldContain() = root.domainEvents().map { it.javaClass }
    .firstOrNone { it == T::class.java }
    .onNone {
      throw failure(
        expected = Expected(Printed(T::class.java.simpleName)),
        actual = Actual(Printed(domainEventsPrinted())),
        "Expected Domain Event Contain, but not found:"
      )
    }

  inline fun <reified T : DomainEvent> shouldNotContain() =
    root.domainEvents().map { it.javaClass }
      .firstOrNone { it == T::class.java }
      .onSome {
        throw failure(
          expected = Expected(Printed("[]")),
          actual = Actual(Printed(domainEventsPrinted())),
          "Expected Domain Event Not Contain, but found:"
        )
      }

  @PublishedApi
  internal fun domainEventsPrinted(): String {
    val eventNames = root.domainEvents().joinToString(", ") { event -> event.javaClass.simpleName }
    return "[$eventNames]"
  }

  companion object {
    inline fun <TId, TAggregateRoot : AggregateRoot<TId>> assertEvents(
      root: TAggregateRoot,
      block: (AggregateRootAssertion<TId, TAggregateRoot>).() -> Unit
    ) = block(AggregateRootAssertion(root))
  }
}
