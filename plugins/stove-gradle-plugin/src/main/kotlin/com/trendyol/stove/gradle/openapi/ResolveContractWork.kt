package com.trendyol.stove.gradle.openapi

import com.fasterxml.jackson.databind.JavaType
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
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

private val unsafeFileName = Regex("[^a-zA-Z0-9_-]")
private val remoteSchemes = setOf("http", "https")

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
    val snapshot = ContractSnapshot(
      root = URI(parameters.uri.get()),
      inline = parameters.inline.orNull,
      offlineDownloads = if (parameters.offlineMode.get()) cachedDownloads(parameters.cache.asFile.get()) else null,
    )
    snapshot.resolve()
    writeChanged(parameters.downloads.asFile.get(), json.writeValueAsBytes(snapshot.remote))
    val output = parameters.output.asFile.get()
    val bundle = json.createObjectNode().apply {
      put("serverBase", snapshot.serverBase)
      set<ObjectNode>("documents", snapshot.documents)
    }
    if (writeChanged(output, json.writeValueAsBytes(bundle))) {
      Logging.getLogger(javaClass).lifecycle("Updated Stove WireMock contract: ${output.nameWithoutExtension}")
    }
  }
}

/** The previous online build's downloads; empty when there was none, so each remote read fails with a clear message. */
private fun cachedDownloads(cache: File): JsonNode = if (cache.exists()) json.readTree(cache) else json.createObjectNode()

/**
 * Copies the root contract and every document it references into one JSON tree, rewriting each `$ref`
 * to point into that tree. [offlineDownloads] is set in offline mode and replaces HTTP downloads.
 */
private class ContractSnapshot(
  private val root: URI,
  private val inline: String?,
  private val offlineDownloads: JsonNode?,
) {
  /** Remote documents as downloaded, keyed by URL: the next offline build's cache. */
  val remote: ObjectNode = json.createObjectNode()

  /** Every loaded document, keyed by its file name in the snapshot. */
  val documents: ObjectNode = json.createObjectNode()

  private val effective = mutableMapOf<URI, URI>()
  val serverBase: String get() = if (root.scheme in remoteSchemes) (effective[root] ?: root).rawPath.ifEmpty { "/" } else "/"

  private val files = mutableMapOf<URI, String>()
  private val rewritten = Collections.newSetFromMap(IdentityHashMap<ObjectNode, Boolean>())

  /** Schema `$id` base URI to the document and pointer that declare it. */
  private val resources = mutableMapOf<URI, Pair<URI, String>>()
  private val scoped = IdentityHashMap<ObjectNode, ReferenceScope>()
  private val indexed = mutableSetOf<URI>()

  private val references: ContractReferences
  private val schemaIds: Boolean

  init {
    val document = documents.get(load(root))
    references = ContractFormat.detect(document).references(json)
    schemaIds = document.path("openapi").asText().startsWith("3.1.")
  }

  fun resolve() {
    visit(root, "", references.document)
    // References now use snapshot-relative document paths; the original bases must not rebase them again.
    scoped.keys.forEach { it.remove("\$id") }
  }

  /** Loads [uri] once and returns its file name in the snapshot. */
  private fun load(uri: URI): String = files.getOrPut(uri) {
    val name = if (files.isEmpty()) "openapi.json" else {
      val basename = uri.path.substringAfterLast('/').substringBeforeLast('.').replace(unsafeFileName, "_").ifEmpty { "schema" }
      "ref${files.size}/$basename.json"
    }
    val document = read(uri)
    require(document != null && document.isContainerNode) { "Invalid OpenAPI document: $uri" }
    if (uri.scheme != "file") remote.set<JsonNode>(uri.toString(), document.deepCopy())
    effective[uri]?.let { finalUri ->
      remote.withObjectProperty("redirects").put(uri.toString(), finalUri.toString())
      files[finalUri] = name
    }
    documents.set<JsonNode>(name, document)
    name
  }

  private fun read(uri: URI): JsonNode? = when (uri.scheme) {
    "file" -> inline?.takeIf { uri == root }?.let(yaml::readTree) ?: yaml.readTree(File(uri))
    in remoteSchemes -> if (offlineDownloads != null) {
      offlineDownloads.path("redirects").get(uri.toString())?.let { effective[uri] = URI(it.textValue()) }
      requireNotNull(offlineDownloads.get(uri.toString())) { "No cached Stove contract for $uri; build once online" }.deepCopy<JsonNode>()
    } else {
      val connection = uri.toURL().openConnection().apply { connectTimeout = 15000; readTimeout = 30000 }
      connection.getInputStream().use(yaml::readTree).also {
        val finalUri = connection.url.toURI()
        if (finalUri != uri) effective[uri] = finalUri
      }
    }
    else -> error("Unsupported OpenAPI URI scheme: ${uri.scheme}")
  }

  /** Visits the node at [pointer] in [uri] and, recursively, everything it references. */
  private fun visit(uri: URI, pointer: String, kind: JavaType) {
    val name = load(uri)
    val document = documents.get(name)
    val target = document.at(pointer)
    require(!target.isMissingNode) { "Unresolved OpenAPI reference: $uri#$pointer" }
    if (schemaIds && pointer.isNotEmpty()) indexIdentifiers(uri, document, kind)

    // Register sibling schema identifiers before resolving references to them.
    val pending = mutableListOf<Triple<ObjectNode, JavaType, URI>>()
    references.walk(target, kind, ReferenceScope(effective[uri] ?: uri, pointer), scopeIn(uri)) { node, refKind, scope ->
      if (rewritten.add(node)) pending += Triple(node, refKind, scope.base)
    }
    for ((node, refKind, base) in pending) node.put("\$ref", follow(uri, name, node, refKind, base))
  }

  /** A pointer into another document may enter below a schema's `$id`, so register all of that document's identifiers once. */
  private fun indexIdentifiers(uri: URI, document: JsonNode, kind: JavaType) {
    val openApi = document.has("openapi")
    if ((openApi || kind.hasRawClass(Schema::class.java)) && indexed.add(uri)) {
      references.walk(document, if (openApi) references.document else kind, ReferenceScope(effective[uri] ?: uri, ""), scopeIn(uri)) { _, _, _ -> }
    }
  }

  /** Visits the target of one `$ref` found in [uri] and returns that reference rewritten to snapshot file names. */
  private fun follow(uri: URI, name: String, node: ObjectNode, kind: JavaType, base: URI): String {
    val value = node.get("\$ref")
    require(value.isTextual) { "Invalid reference in $uri: $value" }
    val ref = URI(value.asText())
    val location = value.asText().substringBefore('#')
    val resource = if (location.isEmpty()) base else base.resolve(location).normalize()
    val (target, prefix) = resources[resource] ?: (resource to "")
    require(uri.scheme == "file" || target.scheme in remoteSchemes) { "A remote OpenAPI document cannot reference a local file: $uri" }
    val fragment = ref.fragment.orEmpty()
    require(fragment.isEmpty() || fragment.startsWith("/")) { "Unsupported OpenAPI reference anchor: $ref in $uri" }
    val targetPointer = prefix + fragment

    visit(target, targetPointer, kind)
    return snapshotRef(from = name, to = files.getValue(target), targetPointer)
  }

  private fun scopeIn(uri: URI) = { schema: ObjectNode, scope: ReferenceScope -> schemaScope(uri, schema, scope) }

  /** The scope inside [schema]: its `$id` (OpenAPI 3.1 only) becomes the base for relative references. */
  private fun schemaScope(uri: URI, schema: ObjectNode, scope: ReferenceScope): ReferenceScope {
    if (!schemaIds) return scope
    return scoped.getOrPut(schema) {
      val id = schema.get("\$id") ?: return@getOrPut scope
      require(id.isTextual) { "Invalid schema identifier at $uri#${scope.pointer}" }
      val resolved = scope.base.resolve(id.textValue()).normalize()
      val base = URI(resolved.toString().removeSuffix("#"))
      require(base.fragment == null) { "Schema identifier must not have a fragment: $base" }
      val location = uri to scope.pointer
      val declared = resources.putIfAbsent(base, location)
      require(declared == null || declared == location) { "Duplicate schema identifier: $base" }
      scope.copy(base = base)
    }
  }
}

/** A `$ref` from snapshot file [from] to [pointer] in snapshot file [to]; a whole other document needs no fragment. */
private fun snapshotRef(from: String, to: String, pointer: String): String {
  val relative = if (to == from) "" else (Path.of(from).parent ?: Path.of("")).relativize(Path.of(to)).joinToString("/")
  val fragment = if (pointer.isEmpty() && relative.isNotEmpty()) "" else URI(null, "", pointer).toASCIIString()
  return relative + fragment
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
