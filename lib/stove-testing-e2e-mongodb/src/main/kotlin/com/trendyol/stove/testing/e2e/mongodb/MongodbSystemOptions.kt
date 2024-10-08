package com.trendyol.stove.testing.e2e.mongodb

import com.fasterxml.jackson.databind.ObjectMapper
import com.trendyol.stove.testing.e2e.serialization.StoveObjectMapper
import com.trendyol.stove.testing.e2e.system.abstractions.ConfiguresExposedConfiguration
import com.trendyol.stove.testing.e2e.system.abstractions.SystemOptions
import com.trendyol.stove.testing.e2e.system.annotations.StoveDsl

@StoveDsl
data class MongodbSystemOptions(
  val databaseOptions: DatabaseOptions = DatabaseOptions(),
  val container: MongoContainerOptions = MongoContainerOptions(),
  override val configureExposedConfiguration: (MongodbExposedConfiguration) -> List<String>,
  val objectMapper: ObjectMapper = StoveObjectMapper.byConfiguring { registerModule(ObjectIdModule()) }
) : SystemOptions, ConfiguresExposedConfiguration<MongodbExposedConfiguration>
