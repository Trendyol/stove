package com.trendyol.stove.gradle

import org.gradle.testkit.runner.GradleRunner
import java.io.File
import java.nio.file.Files

internal fun testProject(): File = Files.createTempDirectory("stove-consumer").toFile().apply {
  write("settings.gradle.kts", "rootProject.name = \"consumer\"")
}

internal fun File.write(path: String, content: String) = resolve(path).apply {
  parentFile.mkdirs()
  writeText(content.trimIndent())
}

internal fun fixture(path: String): String = checkNotNull(StovePlugin::class.java.getResource(path)) { path }.readText()

internal fun File.gradle(vararg args: String) = GradleRunner.create()
  .withProjectDir(this).withPluginClasspath().withArguments(*args)
