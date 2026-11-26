package com.trendyol.stove.gradle.openapi

import com.fasterxml.jackson.databind.JavaType
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.Paths
import io.swagger.v3.oas.models.callbacks.Callback
import io.swagger.v3.oas.models.media.Schema
import io.swagger.v3.oas.models.responses.ApiResponses

/** Follow Swagger's declared field types without round-tripping (and potentially losing) input data. */
internal class ContractReferences(private val mapper: ObjectMapper) {
  val document: JavaType = mapper.constructType(OpenAPI::class.java)
  private val schema = mapper.constructType(Schema::class.java)
  private val schemaMap = mapper.typeFactory.constructMapType(Map::class.java, String::class.java, Schema::class.java)
  private val fields = mutableMapOf<JavaType, Map<String, JavaType>>()

  fun walk(node: JsonNode, type: JavaType, reference: (ObjectNode, JavaType) -> Unit) {
    if (node.isArray && type.isCollectionLikeType) {
      node.forEach { walk(it, type.contentType, reference) }
      return
    }
    if (node !is ObjectNode) return
    if (type.isMapLikeType) {
      val callback = type.hasRawClass(Callback::class.java)
      if (callback && node.has("\$ref")) reference(node, type)
      val extensible = callback || type.hasRawClass(Paths::class.java) || type.hasRawClass(ApiResponses::class.java)
      node.properties().forEach { (name, child) ->
        if (extensible && name.startsWith("x-")) return@forEach
        if (callback && name == "\$ref") return@forEach
        walk(child, type.contentType, reference)
      }
      return
    }
    // Untyped values (examples, defaults, extensions) are user data, not OpenAPI objects.
    if (!type.rawClass.packageName.startsWith("io.swagger.v3.oas.models")) return
    val properties = fields.getOrPut(type) {
      mapper.serializationConfig.introspect(type).findProperties().associate { it.name to it.primaryType }
    }
    if ("\$ref" in properties && node.has("\$ref")) reference(node, type)
    val isSchema = type.hasRawClass(Schema::class.java)
    node.properties().forEach { (name, child) ->
      val childType = when {
        isSchema && (name == "\$defs" || name == "definitions") -> schemaMap
        isSchema && name == "additionalProperties" -> schema
        else -> properties[name]
      }
      if (childType != null) walk(child, childType, reference)
    }
  }
}
