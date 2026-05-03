package com.trendyol.stove.container

import com.trendyol.stove.system.WithDsl
import com.trendyol.stove.system.abstractions.ReadyStove

fun WithDsl.containerApp(configure: () -> ContainerApplicationOptions): ReadyStove {
  stove.applicationUnderTest(ContainerApplicationUnderTest(configure()))
  return stove
}
