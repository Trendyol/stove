package com.trendyol.stove.gradle.openapi

import com.samskivert.mustache.Mustache
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.PathItem
import io.swagger.v3.oas.models.media.Schema
import io.swagger.v3.oas.models.parameters.Parameter
import io.swagger.v3.oas.models.responses.ApiResponse
import org.openapitools.codegen.languages.KotlinClientCodegen
import org.openapitools.codegen.utils.ModelUtils
import java.io.File
import java.util.regex.Pattern

private val responseNames = mapOf(
  200 to "ok", 201 to "created", 202 to "accepted", 204 to "noContent", 301 to "movedPermanently",
  302 to "found", 304 to "notModified", 400 to "badRequest", 401 to "unauthorized", 403 to "forbidden",
  404 to "notFound", 405 to "methodNotAllowed", 409 to "conflict", 410 to "gone", 415 to "unsupportedMediaType",
  422 to "unprocessableEntity", 429 to "tooManyRequests", 500 to "internalServerError", 502 to "badGateway",
  503 to "serviceUnavailable", 504 to "gatewayTimeout",
)
private val typeName = Regex("""(?<![\w.])[a-zA-Z_]\w*""")
private val jvmMethods = setOf("toString", "hashCode", "getClass", "wait", "notify", "notifyAll", "clone", "finalize")
private val template by lazy {
  checkNotNull(SuiteTemplate::class.java.getResourceAsStream("/openapi/suite.kt.mustache")).bufferedReader().use {
    Mustache.compiler().escapeHTML(false).emptyStringIsFalse(true).compile(it)
  }
}

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
    val operations = endpoints.map { operation(alias, it) }
    target.parentFile.mkdirs()
    target.writeText(template.execute(mapOf("pkg" to pkg, "alias" to alias, "suite" to suite, "operations" to operations)))
  }

  private fun operation(alias: String, endpoint: Endpoint): OperationTemplate {
    val (path, method, op) = endpoint
    val name = op.operationId
    val parameters = op.parameters.orEmpty()
    val body = op.requestBody?.let { ModelUtils.getReferencedRequestBody(api, it).content }?.values?.singleOrNull()
    val inputs = buildList {
      parameters.forEach { add(Input(it.name, it.`in`, it.schema)) }
      body?.let { add(Input("body", "request", it.schema)) }
    }

    val locals = localNames(inputs, name)
    val nullableBody = body?.schema?.let(::isNullable) == true
    val arguments = inputs.zip(locals) { input, local ->
      val suffix = when {
        input.location == "path" -> ""
        input.location == "request" && nullableBody -> "?"
        else -> "? = null"
      }
      "$local: ${typeOf(input.schema)}$suffix"
    }
    val bound = parameters.zip(locals)
    val segments = bound.filter { (p, _) -> p.`in` == "path" }
      .associate { (p, local) -> p.name to "segmentPattern(${wireValue(p, local)})" }
    val constraints = bound.filter { (p, _) -> p.`in` == "query" || p.`in` == "header" }
      .map { (p, local) -> "$local?.let { ${p.`in`}(${literal(p.name)}, ${wireValue(p, "it")}) }" }
    val bodyConstraint = when {
      body == null -> null
      nullableBody -> "body(stubBody(${locals.last()}))"
      else -> "${locals.last()}?.let { body(stubBody(it)) }"
    }
    val signatures = buildList {
      // Nullable contracts distinguish an omitted constraint from an explicitly null JSON value.
      if (nullableBody) add(Signature(arguments.dropLast(1), locals.dropLast(1), constraints))
      add(Signature(arguments, locals, constraints + listOfNotNull(bodyConstraint)))
    }
    require(name !in jvmMethods || signatures.none { it.params.isEmpty() }) {
      "Kotlin operation '$name' at $method $path conflicts with an inherited JVM method; choose another operationId"
    }

    return OperationTemplate(
      name = name,
      label = literal("$alias.$name"),
      missingResponse = literal("Choose a response for $alias.$name"),
      scope = name.replaceFirstChar(Char::uppercase) + "Response",
      method = method,
      target = if (segments.isEmpty()) "path(${literal(path)})" else "pathRegex(${pathPattern(path, segments)})",
      signatures = signatures,
      responses = op.responses.map { (code, r) -> response(code, r, op.responses.keys) },
    )
  }

  /** Backticked Kotlin names for [inputs], in order; a second input with the same name gets its location as a prefix. */
  private fun localNames(inputs: List<Input>, operation: String): List<String> =
    inputs.fold(emptyList()) { taken, input ->
      val base = config.toVarName(input.wireName).trim('`')
      val local = if ("`$base`" in taken) "`${input.location}${base.replaceFirstChar(Char::uppercase)}`" else "`$base`"
      require(local !in taken) { "Duplicate Kotlin parameter $local on $operation" }
      taken + local
    }

  // Exact matching needs the text a client sends; toString() drops zero seconds and uses exponents.
  private fun wireValue(p: Parameter, value: String): String {
    val type = typeOf(p.schema)
    return when {
      !ModelUtils.getReferencedSchema(api, p.schema).enum.isNullOrEmpty() -> "this@Requests.system.scalarParameter($value)"
      type == "java.time.OffsetDateTime" -> "java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME.format($value)"
      type == "java.math.BigDecimal" -> "$value.toPlainString()"
      else -> "$value.toString()"
    }
  }

  private fun response(code: String, response: ApiResponse, declared: Set<String>): ResponseTemplate {
    val media = api.resolveResponse(response).content.orEmpty().entries.singleOrNull()
    val schema = media?.value?.schema
    val status = code.toIntOrNull()
    val params = listOfNotNull(
      "statusCode: Int".takeIf { status == null },
      schema?.let { "body: ${typeOf(it)}" + if (isNullable(it)) "?" else "" },
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
        media.key == "text/plain" -> "text(body)"
        else -> "rawJson(mapper.writeValueAsString(body))" // Also writes null for a nullable body.
      },
    )
  }

  private fun isNullable(schema: Schema<*>): Boolean = ModelUtils.getReferencedSchema(api, schema).let { shape ->
    shape.nullable == true || "null" in shape.types.orEmpty()
  }

  // Qualify model names even inside generics: a response scope can have the same name as a DTO.
  private fun typeOf(schema: Schema<*>): String =
    typeName.replace(config.getTypeDeclaration(schema)) {
      if (it.value in modelNames) "${config.modelPackage()}.${it.value}" else it.value
    }
}

private data class Input(val wireName: String, val location: String, val schema: Schema<*>)

private data class OperationTemplate(
  val name: String, val label: String, val missingResponse: String, val scope: String,
  val method: PathItem.HttpMethod, val target: String,
  val signatures: List<Signature>, val responses: List<ResponseTemplate>,
)

private class Signature(parameters: List<String>, locals: List<String>, val constraints: List<String>) {
  val params = parameters.joinToString()
  val args = locals.joinToString()
}

private data class ResponseTemplate(
  val name: String, val code: String, val status: String, val params: String,
  val guard: String?, val contentType: String?, val body: String,
)

/** Quote the contract's text literally and allow alternate encodings only inside the parameter values. */
private fun pathPattern(path: String, segments: Map<String, String>): String {
  val texts = path.split(pathParameter).map { literal(Pattern.quote(it)) }
  val values = pathParameter.findAll(path).map { segments.getValue(it.groupValues[1]) }.toList()
  return buildList {
    texts.zip(values).forEach { (text, value) -> add(text); add(value) }
    add(texts.last())
  }.joinToString(" + ")
}

// Exact codes take precedence over ranges, and both take precedence over the default.
private fun statusGuard(code: String, declared: Set<String>): String {
  return buildList {
    add(if (code == "default") "statusCode in 100..599" else "statusCode / 100 == ${code.first()}")
    declared.forEach { other ->
      when {
        other.toIntOrNull() != null -> add("statusCode != $other")
        code == "default" && other.endsWith("XX") -> add("statusCode / 100 != ${other.first()}")
      }
    }
  }.joinToString(" && ")
}

private fun literal(value: String) = value.map { c ->
  when (c) {
    '"', '\\', '$' -> "\\$c"
    in ' '..'~' -> c.toString()
    else -> "\\u%04x".format(c.code)
  }
}.joinToString("", prefix = "\"", postfix = "\"")
