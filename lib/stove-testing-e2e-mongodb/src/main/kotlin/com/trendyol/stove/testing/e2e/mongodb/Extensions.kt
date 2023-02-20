package com.trendyol.stove.testing.e2e.mongodb

import arrow.core.getOrElse
import com.trendyol.stove.testing.e2e.containers.withProvidedRegistry
import com.trendyol.stove.testing.e2e.system.TestSystem
import com.trendyol.stove.testing.e2e.system.abstractions.SystemNotRegisteredException
import org.testcontainers.containers.MongoDBContainer

fun TestSystem.withMongodb(
    options: MongodbSystemOptions,
): TestSystem {
    val mongodbContainer =
        withProvidedRegistry(options.container.imageWithTag, options.container.registry) {
            MongoDBContainer(it)
        }
    this.getOrRegister(
        MongodbSystem(this, MongodbContext(mongodbContainer, options))
    )
    return this
}

fun TestSystem.withMongodb(
    optionsFn: () -> MongodbSystemOptions,
): TestSystem = withMongodb(optionsFn())

fun TestSystem.mongodb(): MongodbSystem =
    getOrNone<MongodbSystem>().getOrElse {
        throw SystemNotRegisteredException(MongodbSystem::class)
    }
