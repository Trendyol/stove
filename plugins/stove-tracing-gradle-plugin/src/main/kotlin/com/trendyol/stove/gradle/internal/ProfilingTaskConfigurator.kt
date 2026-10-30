package com.trendyol.stove.gradle.internal

import com.trendyol.stove.gradle.StoveTracingExtension
import org.gradle.api.artifacts.Configuration
import org.gradle.api.tasks.testing.Test

internal object ProfilingTaskConfigurator {
  fun configure(testTask: Test, agent: Configuration, extension: StoveTracingExtension) {
    val profiling = extension.profiling
    require(profiling.uploadIntervalSeconds.get() in 1..3600) { "Profiling upload interval must be 1..3600 seconds" }
    val endpoint = java.net.URI(profiling.serverUrl.get())
    require(endpoint.scheme in listOf("http", "https") && endpoint.host != null) { "Profiling serverUrl must be an HTTP(S) URL" }
    val agentPath = agent.singleFile.absolutePath
    val properties = mapOf(
      "stove.profiling.enabled" to "true",
      "stove.profiling.server" to profiling.serverUrl.get(),
      "stove.profiling.service" to extension.serviceName.get(),
      "stove.profiling.alloc" to if (profiling.allocations.get() || profiling.liveAllocations.get()) profiling.allocationThreshold.get() else "",
      "stove.profiling.live" to profiling.liveAllocations.get().toString(),
      "stove.profiling.lock" to profiling.lockThreshold.get(),
      "stove.profiling.interval" to profiling.uploadIntervalSeconds.get().toString()
    )
    testTask.doFirst {
      // Premain installs the agent API. DashboardSystem starts collection once it knows the run ID.
      testTask.jvmArgs("-javaagent:$agentPath", "-Dpyroscope.agent.enabled=false")
      testTask.systemProperties(properties)
    }
  }
}
