package com.trendyol.stove.system.abstractions

/** A managed route or other resource which must remain available until the application has stopped. */
interface ApplicationResource : PluggedSystem {
  /** Stop admitting work and restore active faults before application shutdown. */
  suspend fun beforeApplicationStop()
}
