package com.trendyol.stove.extensions.cucumber

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.platform.engine.discovery.DiscoverySelectors.selectClasspathResource
import org.junit.platform.testkit.engine.EngineExecutionResults
import org.junit.platform.testkit.engine.EngineTestKit
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.util.Base64

internal val cucumberConfiguration = mapOf(
  "cucumber.glue" to "com.trendyol.stove.extensions.cucumber,com.trendyol.stove.cucumberfixtures.probe",
  "cucumber.glue.hint.enabled" to "false",
  "cucumber.plugin" to StoveCucumberPlugin::class.java.name,
  "cucumber.execution.parallel.enabled" to "true",
  "cucumber.execution.parallel.config.strategy" to "fixed",
  "cucumber.execution.parallel.config.fixed.parallelism" to "2",
  "cucumber.execution.parallel.config.fixed.max-pool-size" to "2"
)

internal fun cucumberEngine(vararg features: String): EngineTestKit.Builder = EngineTestKit.engine("cucumber")
  .selectors(features.map(::selectClasspathResource))
  .configurationParameters(cucumberConfiguration)

internal data class CucumberRun(
  val result: EngineExecutionResults,
  val json: String,
  val attachments: List<String>,
  val stderr: String
)

internal fun EngineTestKit.Builder.withDiagnostics(vararg additionalPlugins: String): CucumberRun {
  val report = Files.createTempFile("stove-cucumber-", ".json")
  val previousError = System.err
  val errors = ByteArrayOutputStream()
  try {
    PrintStream(errors, true, Charsets.UTF_8).use { capture ->
      System.setErr(capture)
      val plugins = listOf(StoveCucumberPlugin::class.java.name, "json:$report") + additionalPlugins
      val result = configurationParameter("cucumber.plugin", plugins.joinToString(",")).execute()
      val json = Files.readString(report)
      val attachments = ObjectMapper().readTree(json)
        .flatMap { it.path("elements") }
        .flatMap { it.path("after") }
        .flatMap { it.path("embeddings") }
        .map { String(Base64.getDecoder().decode(it["data"].asText()), Charsets.UTF_8) }
      return CucumberRun(result, json, attachments, errors.toString(Charsets.UTF_8))
    }
  } finally {
    System.setErr(previousError)
    Files.deleteIfExists(report)
  }
}
