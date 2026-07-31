package com.trendyol.stove.reporting

internal data class VisibleCollectionItems(
  val items: List<IndexedValue<Any?>>,
  val omittedItems: Int
)

internal data class VisibleMapEntries(
  val entries: List<Pair<Any?, Any?>>,
  val omittedEntries: Int
)

internal data class VisibleSnapshots(
  val snapshots: List<SystemSnapshot>,
  val omittedSnapshots: Int
)

internal class ConsoleReportPolicy(
  private val limits: ConsoleReportLimits?
) {
  val maxNestingDepth: Int?
    get() = limits?.maxNestingDepth

  fun limitValue(value: String): String =
    limits?.let { limitReportValue(value, it.maxValueCharacters) } ?: value

  fun limitOutput(output: String): String =
    limits?.let { limitReportOutput(output, it.maxOutputCharacters) } ?: output

  fun selectTimelineEntries(entries: List<ReportEntry>): List<IndexedValue<ReportEntry>> {
    val limit = limits?.maxTimelineEntries
      ?: return entries.mapIndexed { index, entry -> IndexedValue(index, entry) }
    val failures = ArrayDeque<IndexedValue<ReportEntry>>(limit)
    val recentPassed = ArrayDeque<IndexedValue<ReportEntry>>(limit)

    entries.forEachIndexed { index, entry ->
      val target = if (entry.isFailed) failures else recentPassed
      target.addLast(IndexedValue(index, entry))
      if (target.size > limit) target.removeFirst()
    }

    val passedSlots = limit - failures.size
    return (failures + recentPassed.takeLast(passedSlots))
      .sortedBy { it.index }
  }

  fun selectCollectionItems(value: Collection<*>): VisibleCollectionItems {
    val maxItems = limits?.maxCollectionItems
    if (maxItems == null || value.size <= maxItems) {
      return VisibleCollectionItems(
        items = value.mapIndexed { index, item -> IndexedValue(index, item) },
        omittedItems = 0
      )
    }

    val firstVisibleIndex = value.size - maxItems
    val visibleItems = value
      .asSequence()
      .drop(firstVisibleIndex)
      .mapIndexed { index, item -> IndexedValue(firstVisibleIndex + index, item) }
      .toList()
    return VisibleCollectionItems(
      items = visibleItems,
      omittedItems = firstVisibleIndex
    )
  }

  fun selectMapEntries(value: Map<*, *>): VisibleMapEntries {
    val maxEntries = limits?.maxMapEntries
    val visibleEntries = value
      .entries
      .asSequence()
      .let { entries -> maxEntries?.let(entries::take) ?: entries }
      .map { it.key to it.value }
      .toList()
    return VisibleMapEntries(
      entries = visibleEntries,
      omittedEntries = value.size - visibleEntries.size
    )
  }

  fun selectSnapshots(snapshots: List<SystemSnapshot>): VisibleSnapshots {
    val maxSnapshots = limits?.maxSnapshots
      ?: return VisibleSnapshots(snapshots, omittedSnapshots = 0)
    val selected = snapshots.take(maxSnapshots)
    return VisibleSnapshots(
      snapshots = selected,
      omittedSnapshots = snapshots.size - selected.size
    )
  }
}
