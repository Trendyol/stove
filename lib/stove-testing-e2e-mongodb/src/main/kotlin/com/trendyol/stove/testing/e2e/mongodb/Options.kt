package com.trendyol.stove.testing.e2e.mongodb

import arrow.core.getOrElse
import com.trendyol.stove.testing.e2e.containers.*
import com.trendyol.stove.testing.e2e.system.*
import com.trendyol.stove.testing.e2e.system.abstractions.*
import com.trendyol.stove.testing.e2e.system.annotations.StoveDsl
import org.testcontainers.containers.MongoDBContainer

@StoveDsl
data class MongodbExposedConfiguration(
    val connectionString: String,
    val host: String,
    val port: Int,
    val replicaSetUrl: String
) : ExposedConfiguration

@StoveDsl
data class MongodbContext(
    val container: MongoDBContainer,
    val options: MongodbSystemOptions
)

@StoveDsl
data class MongoContainerOptions(
    override val registry: String = DEFAULT_REGISTRY,
    override val image: String = "mongo",
    override val tag: String = "latest",
    override val compatibleSubstitute: String? = null
) : ContainerOptions

@StoveDsl
data class DatabaseOptions(
    val default: DefaultDatabase = DefaultDatabase()
) {
    data class DefaultDatabase(
        val name: String = "stove",
        val collection: String = "stoveCollection"
    )
}

internal fun TestSystem.withMongodb(options: MongodbSystemOptions): TestSystem {
    val mongodbContainer = withProvidedRegistry(
        options.container.imageWithTag,
        options.container.registry,
        options.container.compatibleSubstitute
    ) {
        MongoDBContainer(it)
            .withReuse(this.options.keepDependenciesRunning)
    }
    this.getOrRegister(
        MongodbSystem(this, MongodbContext(mongodbContainer, options))
    )
    return this
}

internal fun TestSystem.mongodb(): MongodbSystem =
    getOrNone<MongodbSystem>().getOrElse {
        throw SystemNotRegisteredException(MongodbSystem::class)
    }

@StoveDsl
fun WithDsl.mongodb(configure: () -> MongodbSystemOptions): TestSystem = this.testSystem.withMongodb(configure())

@StoveDsl
fun WithDsl.mongodbDsl(
    configure: @StoveDsl MongodbOptionsDsl.() -> Unit
): TestSystem = this.testSystem.withMongodb(MongodbOptionsDsl(configure)())

@StoveDsl
suspend fun ValidationDsl.mongodb(validation: @MongoDsl suspend MongodbSystem.() -> Unit): Unit = validation(this.testSystem.mongodb())
