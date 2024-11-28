package com.trendyol.stove.testing.e2e.mongodb

import com.mongodb.MongoClientSettings
import com.trendyol.stove.testing.e2e.serialization.StoveSerde
import com.trendyol.stove.testing.e2e.system.abstractions.*
import com.trendyol.stove.testing.e2e.system.annotations.StoveDsl

@StoveDsl
data class MongodbSystemOptions(
  val databaseOptions: DatabaseOptions = DatabaseOptions(),
  val container: MongoContainerOptions = MongoContainerOptions(),
  val configureClient: (MongoClientSettings.Builder) -> Unit = { },
  val serde: StoveSerde<Any, String> = StoveSerde.jackson.anyJsonStringSerde(
    StoveSerde.jackson.byConfiguring { addModule(ObjectIdModule()) }
  ),
  override val configureExposedConfiguration: (MongodbExposedConfiguration) -> List<String>
) : SystemOptions, ConfiguresExposedConfiguration<MongodbExposedConfiguration>
