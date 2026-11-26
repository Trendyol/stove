package com.trendyol.stove.gradle.openapi

import com.fasterxml.jackson.databind.JavaType
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import io.swagger.v3.oas.models.Paths
import io.swagger.v3.oas.models.callbacks.Callback
import io.swagger.v3.oas.models.media.Schema
import io.swagger.v3.oas.models.responses.ApiResponses

/** Follow Swagger's declared field types without round-tripping (and potentially losing) input data. */
internal open class ContractReferences(private val mapper: ObjectMapper, root: Class<*>) {
  val document = mapper.constructType(root)
  protected val schema = mapper.constructType(Schema::class.java)
  private val schemaMap = mapper.typeFactory.constructMapType(Map::class.java, String::class.java, Schema::class.java)
  private val fields = mutableMapOf<JavaType, Map<String, JavaType>>()

  fun walk(node: JsonNode, declaredType: JavaType, reference: (ObjectNode, JavaType) -> Unit) {
    val type = modelType(declaredType)
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
    val namespace = type.rawClass.packageName
    if (!namespace.startsWith("io.swagger.v3.oas.models") && !namespace.startsWith("io.swagger.models")) return
    val properties = fields.getOrPut(type) {
      mapper.serializationConfig.introspect(type).findProperties().associate { it.name to it.primaryType }
    }
    if (node.has("\$ref") && ("\$ref" in properties || allowsReference(type))) reference(node, type)
    node.properties().forEach { (name, child) ->
      val childType = fieldType(type, name) ?: properties[name]
      if (childType != null) walk(child, childType, reference)
    }
  }

  protected open fun modelType(type: JavaType): JavaType = type
  protected open fun allowsReference(type: JavaType): Boolean = false

  protected open fun fieldType(type: JavaType, name: String): JavaType? =
    if (!type.hasRawClass(Schema::class.java)) null else when (name) {
      "\$defs", "definitions" -> schemaMap
      "additionalProperties" -> schema
      else -> null
    }
}
