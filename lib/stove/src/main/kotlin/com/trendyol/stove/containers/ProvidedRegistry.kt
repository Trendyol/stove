package com.trendyol.stove.containers

import org.testcontainers.utility.DockerImageName

/**
 * Can be set globally
 */
@Suppress("ktlint:standard:property-naming")
var DEFAULT_REGISTRY = "docker.io"

/**
 * Allows a docker image to be sourced from a different registry. [DEFAULT_REGISTRY]
 * Example:
 * ```kotlin
 *  withProvidedRegistry("couchbase/server", registry) {
 *             CouchbaseContainer(it).withBucket(bucketDefinition)
 *         }
 * ```
 */
fun <T> withProvidedRegistry(
  imageName: String,
  registry: String = DEFAULT_REGISTRY,
  compatibleSubstitute: String? = null,
  containerBuilder: (DockerImageName) -> T
): T {
  val trimmedRegistry = registry.trim('/')
  val trimmedImage = imageName.trim('/')

  // Skip prepending the registry when the image already contains a registry
  // (e.g. "mcr.microsoft.com/mssql/server") or when the registry is blank.
  val fullImage = if (trimmedRegistry.isBlank() || containsRegistry(trimmedImage)) {
    trimmedImage
  } else {
    "$trimmedRegistry/$trimmedImage"
  }

  return containerBuilder(
    DockerImageName
      .parse(fullImage)
      .asCompatibleSubstituteFor(compatibleSubstitute ?: imageName)
  )
}

/**
 * Heuristic: an image name contains a registry if the part before the first `/`
 * includes a dot (e.g. `mcr.microsoft.com`, `ghcr.io`, `registry.example.com`)
 * or a colon for port (e.g. `localhost:5000`).
 */
private fun containsRegistry(imageName: String): Boolean {
  val firstSegment = imageName.substringBefore('/')
  return firstSegment != imageName && (firstSegment.contains('.') || firstSegment.contains(':'))
}
