package com.trendyol.stove.gradle.openapi

import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.Operation
import io.swagger.v3.oas.models.PathItem
import io.swagger.v3.oas.models.media.Content
import io.swagger.v3.oas.models.media.Schema
import io.swagger.v3.oas.models.parameters.Parameter
import io.swagger.v3.oas.models.servers.Server
import org.openapitools.codegen.languages.KotlinClientCodegen
import org.openapitools.codegen.utils.ModelUtils
import java.net.URI

internal val pathParameter = Regex("""\{([^}]+)}""")
private val nonIdentifier = Regex("[^a-zA-Z0-9]+")
private val responseStatus = Regex("[1-5](?:[0-9]{2}|XX)|default")
private val jvmMethods = setOf("toString", "hashCode", "getClass", "wait", "notify", "notifyAll", "clone", "finalize")
private val parameterLocations = setOf("path", "query", "header")
private val binaryFormats = setOf("binary", "byte")
private val reservedHeaders = setOf("accept", "content-type", "authorization")

internal data class Endpoint(val path: String, val method: PathItem.HttpMethod, val operation: Operation)

internal data class SupportedOperations(val endpoints: List<Endpoint>, val skipped: List<String>)

/**
 * Keeps the operations a typed stub can represent and removes the others, and the schemas only they use, from [api].
 * Runs before normalization: an unsupported operation must never become a broader stub.
 */
internal fun supportedOperations(api: OpenAPI, kotlin: KotlinClientCodegen, mappings: Set<String>): SupportedOperations {
  val candidates = api.paths.orEmpty().flatMap { (path, item) ->
    item.readOperationsMap().map { (method, op) ->
      val name = kotlin.toOperationId(op.operationId ?: "${method.name.lowercase()}_${path.replace(nonIdentifier, "_")}")
      Candidate(path, item, method, op, name)
    }
  }
  val names = mutableSetOf<String>()
  candidates.forEach { c -> require(names.add(c.name)) { "Duplicate Kotlin operation '${c.name}' at ${c.method} ${c.path}" } }
  // The generator reads the swagger model, so names and merged parameters are written back into it.
  candidates.forEach { (path, item, method, op, name) ->
    op.operationId = name
    op.parameters = mergedParameters(api, item, op)
    // A generated function without arguments cannot override a JVM method.
    require(op.parameters.isNotEmpty() || op.requestBody != null || name !in jvmMethods) {
      "Kotlin operation '$name' at $method $path conflicts with an inherited JVM method; choose another operationId"
    }
  }
  api.paths.orEmpty().values.forEach { it.parameters = null } // Already merged into each operation.

  val (kept, rejected) = candidates.map { it to it.support(api, mappings) }.partition { (_, result) -> result.isSuccess }
  rejected.forEach { (c, _) -> c.item.operation(c.method, null) }
  val supported = kept.map { (_, result) -> result.getOrThrow() }
  api.components?.schemas?.keys?.retainAll(supported.flatMap { it.models }.toSet())
  return SupportedOperations(
    endpoints = supported.map { it.endpoint },
    skipped = rejected.map { (c, result) -> "Skipping ${c.method} ${c.path} (${c.name}): ${result.exceptionOrNull()?.message}" },
  )
}

private data class Candidate(
  val path: String, val item: PathItem, val method: PathItem.HttpMethod, val operation: Operation, val name: String,
)

private class Supported(val endpoint: Endpoint, val models: Set<String>)

/** Fails with the reason when the operation, or its server URL, cannot be represented. */
private fun Candidate.support(api: OpenAPI, mappings: Set<String>): Result<Supported> = try {
  val models = OperationCheck(api, mappings).check(operation, path)
  val servers = operation.servers ?: item.servers ?: api.servers
  Result.success(Supported(Endpoint(serverPath(servers) + path, method, operation), models))
} catch (e: IllegalArgumentException) {
  Result.failure(e)
}

/**
 * Path-level parameters plus the operation's own; the operation's wins when `in` and name match.
 * OpenAPI 3 ignores Accept, Content-Type and Authorization header parameters: media types and security set them.
 */
private fun mergedParameters(api: OpenAPI, item: PathItem, op: Operation): List<Parameter> =
  (item.parameters.orEmpty() + op.parameters.orEmpty())
    .map { ModelUtils.getReferencedParameter(api, it) }
    .filterNot { it.`in` == "header" && it.name.lowercase() in reservedHeaders }
    .associateBy { it.`in` to it.name }
    .values
    .toList()

/** Checks one operation; throws [IllegalArgumentException] with the reason when it cannot be represented. */
private class OperationCheck(private val api: OpenAPI, private val mappings: Set<String>) {
  // Visited set for one operation's schema graph; it must be shared across branches to stop on recursive schemas.
  private val used = mutableSetOf<String>()

  /** Returns the component schemas the operation references. */
  fun check(op: Operation, path: String): Set<String> {
    val params = op.parameters.orEmpty()
    params.forEach(::checkParameter)
    val bound = params.filter { it.`in` == "path" }.map { it.name }.toSet()
    require(pathParameter.findAll(path).map { it.groupValues[1] }.toSet() == bound) { "unbound path parameter" }

    op.requestBody?.let { checkContent(ModelUtils.getReferencedRequestBody(api, it).content, allowText = false) }
    require(!op.responses.isNullOrEmpty()) { "no responses" }
    op.responses.forEach { (code, response) ->
      require(code.matches(responseStatus)) { "unsupported response status: $code" }
      checkContent(ModelUtils.getReferencedApiResponse(api, response).content, allowText = true)
    }
    return used.toSet()
  }

  private fun checkParameter(p: Parameter) {
    val schema = requireNotNull(p.schema) { "unsupported content parameter ${p.name}" }.let { singleRef(it) ?: it }
    p.schema = schema // The generator types the parameter from the unwrapped schema.
    val shape = ModelUtils.getReferencedSchema(api, schema)
    val style = if (p.`in` == "query") "form" else "simple"
    require(
      p.`in` in parameterLocations && shape != null &&
        !ModelUtils.isArraySchema(shape) && !ModelUtils.isObjectSchema(shape) &&
        (p.style == null || p.style.toString() == style)
    ) { "unsupported ${p.`in`} parameter ${p.name}" }
    require(shape.enum.isNullOrEmpty() || schema.`$ref` != null) { "inline parameter enum: ${p.name}" }
    checkSchema(schema)
  }

  /** Keeps only the media type a stub serves: the first JSON one, else a plain-text string response. */
  private fun checkContent(content: Content?, allowText: Boolean) {
    if (content.isNullOrEmpty()) return
    val json = content.keys.firstOrNull { it == "application/json" || it.endsWith("+json") }
    val text = "text/plain".takeIf { allowText && json == null && isString(content[it]?.schema) }
    val media = requireNotNull(json ?: text) { "unsupported media types: ${content.keys.joinToString()}" }
    content.keys.retainAll(setOf(media)) // The generator and the suite read the one media type left.
    checkSchema(content.getValue(media).schema)
  }

  private fun isString(schema: Schema<*>?) =
    schema != null && ModelUtils.getReferencedSchema(api, schema).let { ModelUtils.isStringSchema(it) && it.format !in binaryFormats }

  private fun checkSchema(schema: Schema<*>?) {
    requireNotNull(schema) { "missing JSON schema" }
    schema.`$ref`?.let { ref ->
      // Each component is checked once, which also stops on recursive schemas; mapped types are user classes.
      val key = ModelUtils.getSimpleRef(ref)
      if (used.add(key) && key !in mappings) checkSchema(api.components?.schemas?.get(key))
      return
    }
    singleRef(schema)?.let { return checkSchema(it) }
    require(
      schema.oneOf.isNullOrEmpty() && schema.anyOf.isNullOrEmpty() && schema.allOf.isNullOrEmpty() &&
        schema.not == null && schema.types.orEmpty().minus("null").size <= 1
    ) { "composed schema" }
    require(schema.format !in binaryFormats) { "binary schema" }
    schema.properties.orEmpty().values.forEach(::checkSchema)
    schema.items?.let(::checkSchema)
    (schema.additionalProperties as? Schema<*>)?.let(::checkSchema)
  }
}

/** The target of `allOf: [$ref]` or `anyOf: [$ref, {type: null}]`: wrappers around one schema, not unions. */
private fun singleRef(schema: Schema<*>): Schema<*>? {
  if (schema.oneOf != null || schema.not != null || schema.properties != null || schema.additionalProperties != null) return null
  val member = when {
    schema.anyOf == null -> schema.allOf?.singleOrNull()
    schema.allOf == null && schema.anyOf.size == 2 && schema.anyOf.any(::isNullType) -> schema.anyOf.single { !isNullType(it) }
    else -> null
  }
  return member?.takeIf { it.`$ref` != null }
}

private fun isNullType(schema: Schema<*>) = schema.type == "null" || schema.types == setOf("null")

/** The path part of the first server URL, with variables replaced by their defaults. */
private fun serverPath(servers: List<Server>?): String {
  val server = servers?.firstOrNull() ?: return ""
  val url = pathParameter.replace(server.url) {
    requireNotNull(server.variables?.get(it.groupValues[1])?.default) { "missing server variable default: ${it.value}" }
  }
  return URI.create(url).rawPath.orEmpty().trim('/').let { if (it.isEmpty()) "" else "/$it" }
}
