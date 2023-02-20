package com.trendyol.stove.testing.e2e.mongodb

import com.trendyol.stove.testing.e2e.containers.ContainerOptions
import com.trendyol.stove.testing.e2e.containers.DEFAULT_REGISTRY
import com.trendyol.stove.testing.e2e.system.abstractions.ExposedConfiguration
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
