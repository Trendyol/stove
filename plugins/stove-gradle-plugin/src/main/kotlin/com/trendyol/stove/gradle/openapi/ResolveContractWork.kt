package com.trendyol.stove.gradle.openapi

import com.fasterxml.jackson.databind.JavaType
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import io.swagger.v3.oas.models.media.Schema
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.logging.Logging
import org.gradle.api.provider.Property
import org.gradle.workers.WorkAction
import org.gradle.workers.WorkParameters
import java.io.File
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Collections
import java.util.IdentityHashMap

interface ResolveParameters : WorkParameters {
  val uri: Property<String>
  val inline: Property<String>
  val offlineMode: Property<Boolean>
  val cache: RegularFileProperty
  val output: RegularFileProperty
  val downloads: RegularFileProperty
}

/** Freeze the reference graph before codegen so an unchanged root cannot hide referenced drift. */
abstract class ResolveContractWork : WorkAction<ResolveParameters> {
  override fun execute() {
    val json = ObjectMapper()
    val cached = parameters.cache.asFile.get()
    val offline = parameters.offlineMode.get()
    val previous = if (offline && cached.exists()) json.readTree(cached) else null
    val remote = json.createObjectNode()
    val documents = json.createObjectNode()
    val files = mutableMapOf<URI, String>()
    val rewritten = Collections.newSetFromMap(IdentityHashMap<ObjectNode, Boolean>())
    val root = URI(parameters.uri.get())
    val yaml = ObjectMapper(YAMLFactory())

    fun load(uri: URI): String = files.getOrPut(uri) {
      val name = if (files.isEmpty()) "openapi.json" else {
        val basename = uri.path.substringAfterLast('/').substringBeforeLast('.')
          .replace(Regex("[^a-zA-Z0-9_-]"), "_").ifEmpty { "schema" }
        "ref${files.size}/$basename.json"
      }
      val document = when (uri.scheme) {
        "file" -> parameters.inline.orNull?.takeIf { uri == root }?.let(yaml::readTree) ?: yaml.readTree(File(uri))
        "http", "https" -> if (offline) {
          requireNotNull(previous?.get(uri.toString())) { "No cached Stove contract for $uri; build once online" }.deepCopy<JsonNode>()
        } else {
          val connection = uri.toURL().openConnection().apply { connectTimeout = 15000; readTimeout = 30000 }
          connection.getInputStream().use(yaml::readTree)
        }
        else -> error("Unsupported OpenAPI URI scheme: ${uri.scheme}")
      }
      require(document != null && document.isContainerNode) { "Invalid OpenAPI document: $uri" }
      if (uri.scheme != "file") remote.set<JsonNode>(uri.toString(), document.deepCopy())
      documents.set<JsonNode>(name, document)
      name
    }

    val rootDocument = documents.get(load(root))
    val references = ContractFormat.detect(rootDocument).references(json)
    val schemaIds = rootDocument.path("openapi").asText().startsWith("3.1.")
    val resources = mutableMapOf<URI, Pair<URI, String>>()
    val scoped = IdentityHashMap<ObjectNode, ReferenceScope>()

    val indexed = mutableSetOf<URI>()
    fun schemaScope(uri: URI, schema: ObjectNode, scope: ReferenceScope): ReferenceScope {
      if (!schemaIds) return scope
      return scoped.getOrPut(schema) {
        val id = schema.get("\$id") ?: return@getOrPut scope
        require(id.isTextual) { "Invalid schema identifier at $uri#${scope.pointer}" }
        val base = scope.base.resolve(id.textValue()).normalize()
        require(base.fragment == null) { "Schema identifier must not have a fragment: $base" }
        val location = uri to scope.pointer
        val previousLocation = resources.putIfAbsent(base, location)
        require(previousLocation == null || previousLocation == location) { "Duplicate schema identifier: $base" }
        scope.copy(base = base)
      }
    }

    fun visit(uri: URI, pointer: String, kind: JavaType) {
      val name = load(uri)
      val document = documents.get(name)
      val targetNode = document.at(pointer)
      require(!targetNode.isMissingNode) { "Unresolved OpenAPI reference: $uri#$pointer" }
      val scopeFor = { schema: ObjectNode, scope: ReferenceScope -> schemaScope(uri, schema, scope) }
      // A pointer into another document may enter below a schema's $id.
      if (schemaIds && pointer.isNotEmpty() && (document.has("openapi") || kind.hasRawClass(Schema::class.java)) && indexed.add(uri)) {
        val rootKind = if (document.has("openapi")) references.document else kind
        references.walk(document, rootKind, ReferenceScope(uri, ""), scopeFor) { _, _, _ -> }
      }

      // Register sibling schema identifiers before resolving references to them.
      val pending = mutableListOf<Triple<ObjectNode, JavaType, URI>>()
      references.walk(targetNode, kind, ReferenceScope(uri, pointer), scopeFor) { node, targetKind, scope ->
        if (rewritten.add(node)) pending.add(Triple(node, targetKind, scope.base))
      }
      for ((node, targetKind, base) in pending) {
        val value = node.get("\$ref")
        require(value.isTextual) { "Invalid reference in $uri: $value" }
        val ref = URI(value.asText())
        val location = value.asText().substringBefore('#')
        val resource = if (location.isEmpty()) base else base.resolve(location).normalize()
        val (target, prefix) = resources[resource] ?: (resource to "")
        require(uri.scheme == "file" || target.scheme in setOf("http", "https")) {
          "A remote OpenAPI document cannot reference a local file: $uri"
        }
        val fragment = ref.fragment.orEmpty()
        require(fragment.isEmpty() || fragment.startsWith("/")) { "Unsupported OpenAPI reference anchor: $ref in $uri" }
        val targetPointer = prefix + fragment
        visit(target, targetPointer, targetKind)
        val file = files.getValue(target)
        val relative = if (file == name) "" else (Path.of(name).parent ?: Path.of("")).relativize(Path.of(file)).joinToString("/")
        val suffix = if (targetPointer.isEmpty() && relative.isNotEmpty()) "" else URI(null, "", targetPointer).toASCIIString()
        node.put("\$ref", relative + suffix)
      }
    }

    visit(root, "", references.document)
    // References now use snapshot-relative document paths; the original bases must not rebase them again.
    scoped.keys.forEach { it.remove("\$id") }
    writeChanged(parameters.downloads.asFile.get(), json.writeValueAsBytes(remote))
    val output = parameters.output.asFile.get()
    if (writeChanged(output, json.writeValueAsBytes(documents))) {
      Logging.getLogger(javaClass).lifecycle("Updated Stove WireMock contract: ${output.nameWithoutExtension}")
    }
  }
}

internal fun writeChanged(file: File, bytes: ByteArray): Boolean {
  if (file.isFile && file.readBytes().contentEquals(bytes)) return false
  file.parentFile.mkdirs()
  val temp = Files.createTempFile(file.parentFile.toPath(), "stove-", ".tmp")
  try {
    Files.write(temp, bytes)
    Files.move(temp, file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
  } finally {
    Files.deleteIfExists(temp)
  }
  return true
}
