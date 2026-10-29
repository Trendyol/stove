package com.trendyol.stove.gradle

import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Property
import javax.inject.Inject

/** Continuous profiling sent directly to Stove server; disabled by default. */
abstract class StoveProfilingExtension @Inject constructor(objects: ObjectFactory) {
  val enabled: Property<Boolean> = objects.property(Boolean::class.java).convention(false)
  val serverUrl: Property<String> = objects.property(String::class.java).convention("http://localhost:4040")
  val agentVersion: Property<String> = objects.property(String::class.java).convention("2.9.2")
  val allocations: Property<Boolean> = objects.property(Boolean::class.java).convention(false)
  val liveAllocations: Property<Boolean> = objects.property(Boolean::class.java).convention(false)
  val allocationThreshold: Property<String> = objects.property(String::class.java).convention("512k")
  val lockThreshold: Property<String> = objects.property(String::class.java).convention("")
  val uploadIntervalSeconds: Property<Int> = objects.property(Int::class.java).convention(10)
}
