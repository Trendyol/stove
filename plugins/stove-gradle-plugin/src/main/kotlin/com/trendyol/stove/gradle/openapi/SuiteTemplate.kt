package com.trendyol.stove.gradle.openapi

import com.samskivert.mustache.Mustache
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.PathItem
import io.swagger.v3.oas.models.media.Schema
import io.swagger.v3.oas.models.responses.ApiResponse
import org.openapitools.codegen.languages.KotlinClientCodegen
import org.openapitools.codegen.utils.ModelUtils
import java.io.File

private val responseNames = mapOf(
  200 to "ok", 201 to "created", 202 to "accepted", 204 to "noContent", 301 to "movedPermanently",
  302 to "found", 304 to "notModified", 400 to "badRequest", 401 to "unauthorized", 403 to "forbidden",
  404 to "notFound", 405 to "methodNotAllowed", 409 to "conflict", 410 to "gone", 415 to "unsupportedMediaType",
  422 to "unprocessableEntity", 429 to "tooManyRequests", 500 to "internalServerError", 502 to "badGateway",
  503 to "serviceUnavailable", 504 to "gatewayTimeout",
)

internal class SuiteTemplate(
  private val api: OpenAPI,
  private val config: KotlinClientCodegen,
) {
  private val modelNames = api.components?.schemas.orEmpty().keys.map(config::toModelName).toSet()

  fun writeTo(dir: File, alias: String, endpoints: List<Endpoint>) {
    require(alias !in config.reservedWords()) { "Reserved Kotlin API alias: $alias" }
    val pkg = config.modelPackage()
    val suite = alias.replaceFirstChar(Char::uppercase) + "Wiremock"
    val target = dir.resolve(pkg.replace('.', '/')).resolve("$suite.kt")
    require(!target.exists()) { "Schema name conflicts with generated accessor: $suite" }

    val template = checkNotNull(javaClass.getResourceAsStream("/openapi/suite.kt.mustache")).bufferedReader().use {
      Mustache.compiler().escapeHTML(false).emptyStringIsFalse(true).compile(it)
    }
    val operations = endpoints.map { operation(alias, it) }
    target.parentFile.mkdirs()
    target.writeText(template.execute(mapOf("pkg" to pkg, "alias" to alias, "suite" to suite, "operations" to operations)))
  }

  private fun operation(alias: String, endpoint: Endpoint): OperationTemplate {
    val (path, method, op) = endpoint
    val name = op.operationId
    val arguments = linkedMapOf<String, String>()
    val constraints = mutableListOf<String>()
    val segments = mutableMapOf<String, String>()

    fun argument(wireName: String, location: String, schema: Schema<*>): String {
      val base = config.toVarName(wireName).trim('`')
      val local = if ("`$base`" in arguments) "`$location${base.replaceFirstChar(Char::uppercase)}`" else "`$base`"
      require(local !in arguments) { "Duplicate Kotlin parameter $local on $name" }
      arguments[local] = typeOf(schema) + if (location == "path") "" else "? = null"
      return local
    }

    for (p in op.parameters.orEmpty()) {
      val local = argument(p.name, p.`in`, p.schema)
      val enum = !ModelUtils.getReferencedSchema(api, p.schema).enum.isNullOrEmpty()
      fun wireValue(value: String) = if (enum) "this@Requests.system.scalarParameter($value)" else "$value.toString()"
      when (p.`in`) {
        "path" -> segments[p.name] = "encodeSegment(${wireValue(local)})"
        "query", "header" -> constraints += "$local?.let { ${p.`in`}(${literal(p.name)}, ${wireValue("it")}) }"
      }
    }
    op.requestBody?.let { ModelUtils.getReferencedRequestBody(api, it).content }?.values?.singleOrNull()?.let {
      val body = argument("body", "request", it.schema)
      constraints += "$body?.let { jsonEqualTo(it) }"
    }

    return OperationTemplate(
      name = name,
      label = literal("$alias.$name"),
      missingResponse = literal("Choose a response for $alias.$name"),
      scope = name.replaceFirstChar(Char::uppercase) + "Response",
      params = arguments.entries.joinToString { (name, type) -> "$name: $type" },
      args = arguments.keys.joinToString(),
      method = method,
      path = pathExpression(path, segments),
      constraints = constraints,
      responses = op.responses.map { (code, r) -> response(code, r, op.responses.keys) },
    )
  }

  private fun response(code: String, response: ApiResponse, declared: Set<String>): ResponseTemplate {
    val media = ModelUtils.getReferencedApiResponse(api, response).content.orEmpty().entries.singleOrNull()
    val schema = media?.value?.schema
    val shape = schema?.let { ModelUtils.getReferencedSchema(api, it) }
    val nullable = shape?.nullable == true || "null" in shape?.types.orEmpty()
    val status = code.toIntOrNull()
    val params = listOfNotNull(
      "statusCode: Int".takeIf { status == null },
      schema?.let { "body: ${typeOf(it)}" + if (nullable) "?" else "" },
    )
    return ResponseTemplate(
      name = if (code == "default") "defaultResponse" else responseNames[status] ?: "status$code",
      code = code,
      status = status?.toString() ?: "statusCode",
      params = params.joinToString(),
      guard = if (status == null) statusGuard(code, declared) else null,
      contentType = media?.let { literal(it.key) },
      body = when {
        media == null -> "empty()"
        nullable -> "if (body == null) jsonNull() else json(body)"
        else -> "json(body)"
      },
    )
  }

  // Qualify model names even inside generics: a response scope can have the same name as a DTO.
  private fun typeOf(schema: Schema<*>): String =
    Regex("""(?<![\w.])[a-zA-Z_]\w*""").replace(config.getTypeDeclaration(schema)) {
      if (it.value in modelNames) "${config.modelPackage()}.${it.value}" else it.value
    }
}

private data class OperationTemplate(
  val name: String, val label: String, val missingResponse: String, val scope: String,
  val params: String, val args: String, val method: PathItem.HttpMethod, val path: String,
  val constraints: List<String>, val responses: List<ResponseTemplate>,
)

private data class ResponseTemplate(
  val name: String, val code: String, val status: String, val params: String,
  val guard: String?, val contentType: String?, val body: String,
)

private fun pathExpression(path: String, segments: Map<String, String>) = buildList {
  var start = 0
  for (match in pathParameter.findAll(path)) {
    add(literal(path.substring(start, match.range.first)))
    add(segments.getValue(match.groupValues[1]))
    start = match.range.last + 1
  }
  add(literal(path.substring(start)))
}.joinToString(" + ")

// Exact codes take precedence over ranges, and both take precedence over the default.
private fun statusGuard(code: String, declared: Set<String>) = buildList {
  add(if (code == "default") "statusCode in 100..599" else "statusCode / 100 == ${code.first()}")
  for (other in declared) {
    if (other.toIntOrNull() != null) add("statusCode != $other")
    else if (code == "default" && other.endsWith("XX")) add("statusCode / 100 != ${other.first()}")
  }
}.joinToString(" && ")

private fun literal(value: String) = buildString {
  append('"')
  for (c in value) when (c) {
    '"', '\\', '$' -> append('\\').append(c)
    in ' '..'~' -> append(c)
    else -> append("\\u%04x".format(c.code))
  }
  append('"')
}
