package com.trendyol.stove.testing.e2e.mongodb

import com.fasterxml.jackson.databind.ObjectMapper
import com.trendyol.stove.testing.e2e.serialization.StoveObjectMapper
import com.trendyol.stove.testing.e2e.system.abstractions.ConfiguresExposedConfiguration
import com.trendyol.stove.testing.e2e.system.abstractions.SystemOptions

data class MongodbSystemOptions(
    val databaseOptions: DatabaseOptions = DatabaseOptions(),
    val container: MongoContainerOptions = MongoContainerOptions(),
    override val configureExposedConfiguration: (MongodbExposedConfiguration) -> List<String> = { _ -> listOf() },
    val objectMapper: ObjectMapper = StoveObjectMapper.byConfiguring { registerModule(ObjectIdModule()) },
) : SystemOptions, ConfiguresExposedConfiguration<MongodbExposedConfiguration>
