import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction

abstract class GenerateDashboardVersionSourceTask : DefaultTask() {
  @get:Input
  abstract val stoveCompatibilityVersion: Property<String>

  @get:OutputDirectory
  abstract val outputDir: DirectoryProperty

  @TaskAction
  fun generate() {
    val outputFile = outputDir
      .file("com/trendyol/stove/dashboard/StoveCompatibilityVersion.kt")
      .get()
      .asFile
    outputFile.parentFile.mkdirs()
    outputFile.writeText(
      """
      package com.trendyol.stove.dashboard

      internal object StoveCompatibilityVersion {
        const val VALUE: String = "${stoveCompatibilityVersion.get()}"
      }
      """.trimIndent()
    )
  }
}
