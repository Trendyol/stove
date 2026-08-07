package com.trendyol.stove.chaos

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe

class NetworkPartitionTest :
  FunSpec({
    test("partitions once and heals targets in reverse order") {
      val events = mutableListOf<String>()
      val dc1 = RecordingTarget("dc1", events)
      val dc2 = RecordingTarget("dc2", events)

      withNetworkPartition(dc1, dc2, dc1) {
        events += "experiment"
      }

      events.shouldContainExactly(
        "partition:dc1",
        "partition:dc2",
        "experiment",
        "heal:dc2",
        "heal:dc1"
      )
    }

    test("heals an already partitioned target when a later partition fails") {
      val events = mutableListOf<String>()
      val dc1 = RecordingTarget("dc1", events)
      val dc2 = RecordingTarget("dc2", events, partitionFailure = IllegalStateException("cannot partition"))

      shouldThrow<IllegalStateException> {
        withNetworkPartition(dc1, dc2) { events += "experiment" }
      }.message shouldBe "cannot partition"

      events.shouldContainExactly("partition:dc1", "partition:dc2", "heal:dc1")
    }

    test("preserves the experiment failure when healing also fails") {
      val events = mutableListOf<String>()
      val experimentFailure = IllegalArgumentException("experiment failed")
      val target = RecordingTarget(
        name = "dc1",
        events = events,
        healFailure = IllegalStateException("heal failed")
      )

      val actual = shouldThrow<IllegalArgumentException> {
        withNetworkPartition(target) { throw experimentFailure }
      }

      actual shouldBe experimentFailure
      actual.suppressed.single().message shouldBe "heal failed"
      events.shouldContainExactly("partition:dc1", "heal:dc1")
    }
  })

private class RecordingTarget(
  override val name: String,
  private val events: MutableList<String>,
  private val partitionFailure: Throwable? = null,
  private val healFailure: Throwable? = null
) : NetworkPartitionTarget {
  override fun partition() {
    events += "partition:$name"
    partitionFailure?.let { throw it }
  }

  override fun heal() {
    events += "heal:$name"
    healFailure?.let { throw it }
  }
}
