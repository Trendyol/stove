package com.trendyol.stove.gradle.openapi

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode

/** Only follow reference-bearing fields. Examples, defaults and extensions may contain arbitrary JSON. */
internal enum class ContractNode {
  DOCUMENT, COMPONENTS, PATH_ITEM, OPERATION, PARAMETER, MEDIA, ENCODING, RESPONSE, REQUEST_BODY, SCHEMA, CALLBACK, LEAF;

  fun walk(node: JsonNode, reference: (ObjectNode, ContractNode) -> Unit) {
    if (node !is ObjectNode) return
    fun child(key: String, kind: ContractNode) = node.get(key)?.let { kind.walk(it, reference) }
    fun entries(key: String, kind: ContractNode) = node.get(key)?.forEach { kind.walk(it, reference) }

    if (this in referenceable && node.has("\$ref")) reference(node, this)
    when (this) {
      DOCUMENT -> {
        node.path("paths").properties().filter { it.key.startsWith('/') }.forEach { PATH_ITEM.walk(it.value, reference) }
        entries("webhooks", PATH_ITEM)
        child("components", COMPONENTS)
      }
      COMPONENTS -> {
        entries("schemas", SCHEMA)
        entries("parameters", PARAMETER)
        entries("headers", PARAMETER)
        entries("responses", RESPONSE)
        entries("requestBodies", REQUEST_BODY)
        entries("callbacks", CALLBACK)
        entries("pathItems", PATH_ITEM)
        for (key in listOf("examples", "links", "securitySchemes")) entries(key, LEAF)
      }
      PATH_ITEM -> {
        for (method in listOf("get", "post", "put", "delete", "patch", "head", "options", "trace")) child(method, OPERATION)
        entries("parameters", PARAMETER)
      }
      OPERATION -> {
        child("requestBody", REQUEST_BODY)
        entries("parameters", PARAMETER)
        entries("callbacks", CALLBACK)
        node.path("responses").properties().filterNot { it.key.startsWith("x-") }.forEach { RESPONSE.walk(it.value, reference) }
      }
      PARAMETER, MEDIA -> {
        child("schema", SCHEMA)
        entries("examples", LEAF)
        if (this == MEDIA) entries("encoding", ENCODING) else entries("content", MEDIA)
      }
      RESPONSE, REQUEST_BODY -> {
        entries("content", MEDIA)
        if (this == RESPONSE) {
          entries("headers", PARAMETER)
          entries("links", LEAF)
        }
      }
      ENCODING -> entries("headers", PARAMETER)
      CALLBACK -> node.properties().filterNot { it.key.startsWith("x-") || it.key == "\$ref" }
        .forEach { PATH_ITEM.walk(it.value, reference) }
      SCHEMA -> {
        for (key in listOf("properties", "patternProperties", "dependentSchemas", "\$defs", "definitions")) entries(key, SCHEMA)
        for (key in listOf("allOf", "oneOf", "anyOf", "prefixItems")) entries(key, SCHEMA)
        for (key in listOf("items", "contains", "additionalProperties", "unevaluatedProperties", "unevaluatedItems",
          "propertyNames", "not", "if", "then", "else", "contentSchema")) child(key, SCHEMA)
      }
      LEAF -> Unit
    }
  }

  private companion object {
    val referenceable = setOf(PATH_ITEM, PARAMETER, RESPONSE, REQUEST_BODY, SCHEMA, CALLBACK, LEAF)
  }
}
