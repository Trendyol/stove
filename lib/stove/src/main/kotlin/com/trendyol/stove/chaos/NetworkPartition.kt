package com.trendyol.stove.chaos

/** A named application-to-dependency network link that can be partitioned and healed. */
interface NetworkPartitionTarget {
  val name: String

  fun partition()

  fun heal()
}

/**
 * Runs an experiment with the selected network links partitioned.
 *
 * Every successfully partitioned target is healed in reverse order when the experiment finishes,
 * fails, or is cancelled. If recovery also fails, the experiment failure remains primary and the
 * recovery failure is attached as suppressed evidence.
 */
suspend fun <T> withNetworkPartition(
  vararg targets: NetworkPartitionTarget,
  experiment: suspend () -> T
): T {
  val partitionedTargets = mutableListOf<NetworkPartitionTarget>()
  var experimentFailure: Throwable? = null

  try {
    targets.distinct().forEach { target ->
      target.partition()
      partitionedTargets += target
    }
    return experiment()
  } catch (failure: Throwable) {
    experimentFailure = failure
    throw failure
  } finally {
    val recoveryFailure = partitionedTargets
      .asReversed()
      .mapNotNull { target -> runCatching(target::heal).exceptionOrNull() }
      .reduceOrNull { first, next -> first.apply { addSuppressed(next) } }

    if (recoveryFailure != null) {
      experimentFailure?.addSuppressed(recoveryFailure) ?: throw recoveryFailure
    }
  }
}
