package com.trendyol.stove.events

import kotlinx.coroutines.Job

typealias EventHandler<E> = (E) -> Unit
typealias StoveEventHandler = EventHandler<StoveEvent>

internal typealias SnapshotRecorder = (Job, SnapshotRecorded) -> Unit
internal typealias EventDrain = suspend () -> Long

internal typealias SnapshotCapture = Job.() -> Unit
internal typealias SnapshotDeadline = suspend (suspend () -> Unit) -> Boolean
