package com.trendyol.stove.reporting

import java.util.Collections
import java.util.IdentityHashMap

internal enum class ContainerVisit {
  ENTERED,
  CYCLE,
  MAX_DEPTH
}

internal class ConsoleRenderContext(
  private val maxDepth: Int?
) {
  private val ancestors: MutableSet<Any> =
    Collections.newSetFromMap(IdentityHashMap())

  fun enter(
    value: Any,
    depth: Int
  ): ContainerVisit = when {
    maxDepth != null && depth >= maxDepth -> ContainerVisit.MAX_DEPTH
    !ancestors.add(value) -> ContainerVisit.CYCLE
    else -> ContainerVisit.ENTERED
  }

  fun leave(value: Any) {
    ancestors.remove(value)
  }
}
