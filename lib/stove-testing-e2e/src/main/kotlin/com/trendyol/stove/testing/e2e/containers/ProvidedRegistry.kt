package com.trendyol.stove.testing.e2e.containers

import org.testcontainers.utility.DockerImageName

/**
 * Can be set globally
 */
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
    containerBuilder: (DockerImageName) -> T,
): T = containerBuilder(
    DockerImageName
        .parse(registry.trim('/') + '/' + imageName.trim('/'))
        .asCompatibleSubstituteFor(compatibleSubstitute ?: imageName)
)
