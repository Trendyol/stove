package com.trendyol.stove.events

typealias StoveEventHandler = (StoveEvent) -> Unit

internal typealias SnapshotRecorder = (SnapshotRecorded) -> Unit
internal typealias EventDrain = suspend () -> Long
