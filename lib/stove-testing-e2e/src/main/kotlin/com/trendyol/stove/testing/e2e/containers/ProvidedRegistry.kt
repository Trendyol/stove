package com.trendyol.stove.testing.e2e.containers

import org.testcontainers.utility.DockerImageName

const val DEFAULT_REGISTRY = "hub.docker.com"

/**
 * Allows a docker image to be sourced from a different registry. The default is QC [DEFAULT_REGISTRY]
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
    containerBuilder: (DockerImageName) -> T,
): T = containerBuilder(
    DockerImageName.parse(registry + imageName).asCompatibleSubstituteFor(imageName)
)
