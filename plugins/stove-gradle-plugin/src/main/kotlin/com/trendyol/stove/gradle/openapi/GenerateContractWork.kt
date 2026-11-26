package com.trendyol.stove.gradle.openapi

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.swagger.v3.oas.models.OpenAPI
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.workers.WorkAction
import org.gradle.workers.WorkParameters
import org.openapitools.codegen.ClientOptInput
import org.openapitools.codegen.DefaultGenerator
import org.openapitools.codegen.languages.KotlinClientCodegen
import java.io.File

interface GenerateParameters : WorkParameters {
  val input: RegularFileProperty
  val alias: Property<String>
  val types: MapProperty<String, String>
  val output: DirectoryProperty
  val scratch: DirectoryProperty
}

abstract class GenerateContractWork : WorkAction<GenerateParameters> {
  override fun execute() {
    val scratch = parameters.scratch.asFile.get()
    val docs = scratch.resolve("documents")
    val generated = scratch.resolve("kotlin")
    generated.deleteRecursively()
    val api = readContract(ObjectMapper().readTree(parameters.input.asFile.get()), docs)
    val alias = parameters.alias.get()
    val pkg = "stove.generated.wiremock.$alias"
    val config = KotlinClientCodegen().apply {
      outputDir = generated.absolutePath
      inputSpec = docs.resolve("openapi.json").absolutePath
      setModelPackage(pkg)
      additionalProperties().putAll(mapOf("serializationLibrary" to "jackson", "sourceFolder" to ".", "omitGradleWrapper" to true))
      inlineSchemaOption()["RESOLVE_INLINE_ENUMS"] = "true"
      schemaMapping().putAll(parameters.types.get())
    }
    val operations = supportedOperations(api, config, parameters.types.get().keys)
    val generator = DefaultGenerator().apply {
      setGenerateMetadata(false)
      setGeneratorPropertyDefault("models", "true")
      setGeneratorPropertyDefault("modelDocs", "false")
      setGeneratorPropertyDefault("modelTests", "false")
    }
    generator.opts(ClientOptInput().config(config).openAPI(api)).generate()
    SuiteTemplate(api, config).writeTo(generated, alias, operations)
    val output = parameters.output.asFile.get()
    check(output.deleteRecursively()) { "Cannot clean generated Stove directory: $output" }
    generated.copyRecursively(output, overwrite = true)
  }
}

private fun readContract(documents: JsonNode, docs: File): OpenAPI {
  docs.deleteRecursively()
  documents.properties().forEach { (name, tree) ->
    val file = docs.resolve(name)
    require(file.normalize().startsWith(docs) && !File(name).isAbsolute) { "Invalid contract document path: $name" }
    file.parentFile.mkdirs()
    file.writeText(tree.toString())
  }
  val input = docs.resolve("openapi.json")
  return ContractFormat.detect(documents.get("openapi.json")).parse(input)
}
