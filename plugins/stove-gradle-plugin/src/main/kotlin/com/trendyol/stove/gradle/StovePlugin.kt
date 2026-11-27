package com.trendyol.stove.gradle

import com.trendyol.stove.gradle.internal.TestTaskConfigurator
import com.trendyol.stove.gradle.internal.TracingDefaults
import com.trendyol.stove.gradle.openapi.configureWiremock
import org.gradle.api.Action
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.model.ObjectFactory
import javax.inject.Inject

class StovePlugin : Plugin<Project> {
  override fun apply(project: Project) {
    val stove = project.extensions.create("stove", StoveExtension::class.java)
    TestTaskConfigurator.configure(project, stove)
    configureWiremock(project, stove.wiremock)
  }
}

abstract class StoveExtension @Inject constructor(objects: ObjectFactory) {
  val serviceName = objects.property(String::class.java).convention(TracingDefaults.DEFAULT_SERVICE_NAME)
  val testTaskNames = objects.listProperty(String::class.java).convention(emptyList())

  val tracing = objects.newInstance(StoveTracingExtension::class.java)
  val profiling = objects.newInstance(StoveProfilingExtension::class.java)
  val wiremock = objects.newInstance(StoveWiremockExtension::class.java)

  fun tracing(action: Action<StoveTracingExtension>) {
    tracing.enabled.convention(true)
    action.execute(tracing)
  }

  fun profiling(action: Action<StoveProfilingExtension>) {
    profiling.enabled.convention(true)
    action.execute(profiling)
  }

  fun wiremock(action: Action<StoveWiremockExtension>) = action.execute(wiremock)
}
