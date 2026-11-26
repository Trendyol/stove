package com.trendyol.stove.gradle.openapi

import com.fasterxml.jackson.databind.JavaType
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.swagger.models.Model
import io.swagger.models.Path
import io.swagger.models.Response
import io.swagger.models.Swagger
import io.swagger.models.parameters.Parameter
import io.swagger.models.properties.Property
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.parser.OpenAPIV3Parser
import io.swagger.v3.parser.converter.SwaggerConverter
import io.swagger.v3.parser.core.extensions.SwaggerParserExtension
import io.swagger.v3.parser.core.models.ParseOptions
import java.io.File

internal enum class ContractFormat(private val newParser: () -> SwaggerParserExtension) {
  OPENAPI_3(::OpenAPIV3Parser) {
    override fun references(mapper: ObjectMapper) = ContractReferences(mapper, OpenAPI::class.java)
  },
  SWAGGER_2(::SwaggerConverter) {
    override fun references(mapper: ObjectMapper): ContractReferences = Swagger2References(mapper)
  };

  abstract fun references(mapper: ObjectMapper): ContractReferences

  fun parse(input: File): OpenAPI {
    val parsed = newParser().readLocation(input.toURI().toString(), null, ParseOptions().apply { isResolve = true })
    require(parsed.messages.isNullOrEmpty()) { "Invalid Stove OpenAPI contract: ${parsed.messages.joinToString("; ")}" }
    val api = requireNotNull(parsed.openAPI) { "Expected a Swagger 2.0 or OpenAPI 3.0/3.1 contract" }
    require(api.openapi?.matches(Regex("3\\.[01]\\.\\d+.*")) == true) { "Unsupported OpenAPI version: ${api.openapi}" }
    return api
  }

  companion object {
    fun detect(root: JsonNode): ContractFormat = if (root.path("swagger").asText() == "2.0") SWAGGER_2 else OPENAPI_3
  }
}

private class Swagger2References(mapper: ObjectMapper) : ContractReferences(mapper, Swagger::class.java) {
  // Swagger 2 models and properties are polymorphic; Schema describes their shared JSON Schema fields.
  override fun modelType(type: JavaType): JavaType =
    if (type.hasRawClass(Model::class.java) || type.hasRawClass(Property::class.java)) schema else type

  override fun allowsReference(type: JavaType): Boolean =
    type.hasRawClass(Parameter::class.java) || type.hasRawClass(Path::class.java) || type.hasRawClass(Response::class.java)

  override fun fieldType(type: JavaType, name: String): JavaType? =
    if (type.hasRawClass(Parameter::class.java) && (name == "schema" || name == "items")) schema else super.fieldType(type, name)
}
