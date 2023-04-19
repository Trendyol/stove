package com.trendyol.stove.testing.e2e.couchbase

import arrow.core.getOrElse
import com.trendyol.stove.testing.e2e.containers.withProvidedRegistry
import com.trendyol.stove.testing.e2e.system.TestSystem
import com.trendyol.stove.testing.e2e.system.ValidationDsl
import com.trendyol.stove.testing.e2e.system.abstractions.SystemNotRegisteredException
import org.testcontainers.couchbase.BucketDefinition
import org.testcontainers.couchbase.CouchbaseContainer

fun TestSystem.withCouchbase(
    options: CouchbaseSystemOptions,
): TestSystem {
    val bucketDefinition = BucketDefinition(options.defaultBucket)
    val couchbaseContainer =
        withProvidedRegistry("couchbase/server", options.registry) {
            CouchbaseContainer(it).withBucket(bucketDefinition)
                .withReuse(this.options.keepDependenciesRunning)
        }
    this.getOrRegister(
        CouchbaseSystem(this, CouchbaseContext(bucketDefinition, couchbaseContainer, options))
    )
    return this
}

fun TestSystem.couchbase(): CouchbaseSystem =
    getOrNone<CouchbaseSystem>().getOrElse {
        throw SystemNotRegisteredException(CouchbaseSystem::class)
    }

suspend fun ValidationDsl.couchbase(validation: suspend CouchbaseSystem.() -> Unit): Unit =
    validation(this.testSystem.couchbase())
