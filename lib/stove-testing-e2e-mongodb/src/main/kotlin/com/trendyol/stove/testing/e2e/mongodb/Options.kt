package com.trendyol.stove.testing.e2e.mongodb

import arrow.core.getOrElse
import com.trendyol.stove.testing.e2e.containers.ContainerOptions
import com.trendyol.stove.testing.e2e.containers.DEFAULT_REGISTRY
import com.trendyol.stove.testing.e2e.containers.withProvidedRegistry
import com.trendyol.stove.testing.e2e.system.TestSystem
import com.trendyol.stove.testing.e2e.system.ValidationDsl
import com.trendyol.stove.testing.e2e.system.abstractions.ExposedConfiguration
import com.trendyol.stove.testing.e2e.system.abstractions.SystemNotRegisteredException
import org.testcontainers.containers.MongoDBContainer

data class MongodbExposedConfiguration(
    val connectionString: String,
) : ExposedConfiguration

data class MongodbContext(
    val container: MongoDBContainer,
    val options: MongodbSystemOptions,
)

data class MongoContainerOptions(
    override val registry: String = DEFAULT_REGISTRY,
    override val image: String = "mongo",
    override val tag: String = "latest",
) : ContainerOptions {
    override val imageWithTag: String = "$image:$tag"
}

data class DatabaseOptions(
    val default: DefaultDatabase = DefaultDatabase(),
) {
    data class DefaultDatabase(
        val name: String = "stove",
        val collection: String = "stoveCollection",
    )
}

fun TestSystem.withMongodb(
    options: MongodbSystemOptions,
): TestSystem {
    val mongodbContainer =
        withProvidedRegistry(options.container.imageWithTag, options.container.registry) {
            MongoDBContainer(it)
                .withReuse(this.options.keepDependenciesRunning)
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

suspend fun ValidationDsl.mongodb(validation: suspend MongodbSystem.() -> Unit): Unit =
    validation(this.testSystem.mongodb())
