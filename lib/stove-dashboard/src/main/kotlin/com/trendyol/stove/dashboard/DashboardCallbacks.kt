package com.trendyol.stove.dashboard

import com.trendyol.stove.dashboard.api.DashboardEvent

internal typealias DashboardEventBuilder = DashboardEvent.Builder.() -> Unit
internal typealias DashboardEventEmitter = (DashboardEventBuilder) -> Unit
