package com.trendyol.stove.gradle.openapi

import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.Operation
import io.swagger.v3.oas.models.PathItem
import io.swagger.v3.oas.models.media.Content
import io.swagger.v3.oas.models.media.Schema
import io.swagger.v3.oas.models.servers.Server
import org.gradle.api.logging.Logging
import org.openapitools.codegen.languages.KotlinClientCodegen
import org.openapitools.codegen.utils.ModelUtils
import java.net.URI

internal val pathParameter = Regex("""\{([^}]+)}""")

internal data class Endpoint(val path: String, val method: PathItem.HttpMethod, val operation: Operation)

/** Check representability before normalization; an unsupported operation must never become a broader stub. */
internal fun supportedOperations(api: OpenAPI, kotlin: KotlinClientCodegen, mappings: Set<String>): List<Endpoint> {
  val endpoints = mutableListOf<Endpoint>()
  val names = mutableSetOf<String>()
  val models = mutableSetOf<String>()
  for ((path, item) in api.paths.orEmpty()) {
    for ((method, op) in item.readOperationsMap()) {
      val name = kotlin.toOperationId(op.operationId ?: "${method.name.lowercase()}_${path.replace(Regex("[^a-zA-Z0-9]+"), "_")}")
      require(names.add(name)) { "Duplicate Kotlin operation '$name' at $method $path" }
      op.operationId = name
      op.parameters = (item.parameters.orEmpty() + op.parameters.orEmpty())
        .map { ModelUtils.getReferencedParameter(api, it) }
        .associateBy { it.`in` to it.name }.values.toList()
      if (op.parameters.isEmpty() && op.requestBody == null) {
        require(name !in setOf("toString", "hashCode", "getClass", "wait", "notify", "notifyAll", "clone", "finalize")) {
          "Kotlin operation '$name' at $method $path conflicts with an inherited JVM method; choose another operationId"
        }
      }
      try {
        val used = validateOperation(api, op, path, mappings)
        val server = (op.servers ?: item.servers ?: api.servers)?.firstOrNull()
        endpoints += Endpoint(serverPath(server) + path, method, op)
        models += used
      } catch (e: IllegalArgumentException) {
        Logging.getLogger("Stove WireMock").warn("Skipping $method $path ($name): ${e.message}")
        item.operation(method, null)
      }
    }
    item.parameters = null // Already merged; operation parameters override path-level parameters.
  }
  api.components?.schemas?.keys?.retainAll(models)
  return endpoints
}

/** Only retain schemas from operations whose entire request and response set can be represented. */
private fun validateOperation(api: OpenAPI, op: Operation, path: String, mappings: Set<String>): Set<String> {
  val used = mutableSetOf<String>()

  fun checkSchema(schema: Schema<*>?) {
    requireNotNull(schema) { "missing JSON schema" }
    schema.`$ref`?.let { ref ->
      val key = ModelUtils.getSimpleRef(ref)
      if (used.add(key) && key !in mappings) checkSchema(api.components?.schemas?.get(key))
      return
    }
    require(schema.oneOf.isNullOrEmpty() && schema.anyOf.isNullOrEmpty() && schema.allOf.isNullOrEmpty() &&
      schema.not == null && schema.types.orEmpty().minus("null").size <= 1) { "composed schema" }
    require(schema.format !in setOf("binary", "byte")) { "binary schema" }
    schema.properties.orEmpty().values.forEach(::checkSchema)
    schema.items?.let(::checkSchema)
    (schema.additionalProperties as? Schema<*>)?.let(::checkSchema)
  }

  fun checkContent(content: Content?) {
    if (content.isNullOrEmpty()) return
    val media = content.keys.singleOrNull()
    require(media != null && (media == "application/json" || media.endsWith("+json"))) {
      "unsupported media types: ${content.keys.joinToString()}"
    }
    checkSchema(content.getValue(media).schema)
  }

  val params = op.parameters.orEmpty()
  for (p in params) {
    val schema = requireNotNull(p.schema) { "unsupported content parameter ${p.name}" }
    val shape = ModelUtils.getReferencedSchema(api, schema)
    require(p.`in` in setOf("path", "query", "header") && shape != null &&
      !ModelUtils.isArraySchema(shape) && !ModelUtils.isObjectSchema(shape) &&
      (p.style == null || p.style.toString() == if (p.`in` == "query") "form" else "simple")) {
      "unsupported ${p.`in`} parameter ${p.name}"
    }
    require(shape.enum.isNullOrEmpty() || p.schema.`$ref` != null) { "inline parameter enum: ${p.name}" }
    checkSchema(p.schema)
  }
  require(pathParameter.findAll(path).map { it.groupValues[1] }.toSet() ==
    params.filter { it.`in` == "path" }.map { it.name }.toSet()) { "unbound path parameter" }
  op.requestBody?.let { checkContent(ModelUtils.getReferencedRequestBody(api, it).content) }
  require(!op.responses.isNullOrEmpty()) { "no responses" }
  for ((code, response) in op.responses) {
    require(code.matches(Regex("[1-5](?:[0-9]{2}|XX)|default"))) { "unsupported response status: $code" }
    checkContent(ModelUtils.getReferencedApiResponse(api, response).content)
  }
  return used
}

private fun serverPath(server: Server?): String {
  if (server == null) return ""
  val url = pathParameter.replace(server.url) {
    requireNotNull(server.variables?.get(it.groupValues[1])?.default) { "missing server variable default: ${it.value}" }
  }
  return URI.create(url).rawPath.orEmpty().trim('/').let { if (it.isEmpty()) "" else "/$it" }
}
