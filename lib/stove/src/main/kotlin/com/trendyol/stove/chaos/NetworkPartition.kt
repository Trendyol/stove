package com.trendyol.stove.chaos

import com.trendyol.stove.system.closeAll

/** A named application-to-dependency network link that can be partitioned and healed. */
interface NetworkPartitionTarget {
  val name: String

  fun partition()

  fun heal()
}

/**
 * Partitions each distinct target and restores successfully partitioned targets in reverse order.
 * Restoration runs even on cancellation; secondary failures are attached to the original failure.
 */
suspend fun <T> withNetworkPartition(
  vararg targets: NetworkPartitionTarget,
  experiment: suspend () -> T
): T {
  val partitioned = mutableListOf<NetworkPartitionTarget>()
  return restoring(restore = { closeAll(partitioned.asReversed()) { it.heal() } }) {
    targets.distinct().forEach { target ->
      target.partition()
      partitioned += target
    }
    experiment()
  }
}
