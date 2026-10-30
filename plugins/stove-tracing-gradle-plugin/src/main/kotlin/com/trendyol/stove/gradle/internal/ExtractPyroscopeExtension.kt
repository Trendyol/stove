package com.trendyol.stove.gradle.internal

import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault

/** Extract the bundled upstream JAR without changing the consumer's dependency repositories. */
@DisableCachingByDefault(because = "Copying a bundled resource is cheaper than caching it")
abstract class ExtractPyroscopeExtension : DefaultTask() {
  @get:OutputFile
  abstract val destination: RegularFileProperty

  @TaskAction
  fun extract() {
    val target = destination.get().asFile
    target.parentFile.mkdirs()
    checkNotNull(javaClass.getResourceAsStream("/profiling/pyroscope-otel-javaagent-extension.jar")).use { input ->
      target.outputStream().use(input::copyTo)
    }
  }
}
