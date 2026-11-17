package com.trendyol.stove.events

typealias EventHandler<E> = (E) -> Unit
typealias StoveEventHandler = EventHandler<StoveEvent>

internal typealias SnapshotRecorder = (SnapshotRecorded) -> Unit
internal typealias EventDrain = suspend () -> Long

internal typealias SnapshotCapture = () -> Unit
