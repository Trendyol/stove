package com.trendyol.stove.logging

import com.trendyol.stove.system.Stove
import com.trendyol.stove.system.WithDsl
import com.trendyol.stove.system.annotations.StoveDsl

fun WithDsl.logging(
  configure: @StoveDsl () -> LoggingSystemOptions = { LoggingSystemOptions() }
): Stove {
  this.stove.getOrRegister(LoggingSystem(this.stove, configure()))
  return this.stove
}
