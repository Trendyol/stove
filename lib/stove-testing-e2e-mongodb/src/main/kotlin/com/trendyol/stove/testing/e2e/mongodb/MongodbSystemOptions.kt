package com.trendyol.stove.testing.e2e.mongodb

import com.fasterxml.jackson.databind.*
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.mongodb.MongoClientSettings
import com.trendyol.stove.testing.e2e.serialization.StoveSerde
import com.trendyol.stove.testing.e2e.system.abstractions.*
import com.trendyol.stove.testing.e2e.system.annotations.StoveDsl
import org.bson.json.JsonWriterSettings

@StoveDsl
data class MongodbSystemOptions(
  val databaseOptions: DatabaseOptions = DatabaseOptions(),
  val container: MongoContainerOptions = MongoContainerOptions(),
  val configureClient: (MongoClientSettings.Builder) -> Unit = { },
  val serde: StoveSerde<Any, String> = StoveSerde.jackson.anyJsonStringSerde(
    StoveSerde.jackson.byConfiguring {
      disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
      enable(MapperFeature.DEFAULT_VIEW_INCLUSION)
      addModule(ObjectIdModule())
      addModule(KotlinModule.Builder().build())
    }
  ),
  val jsonWriterSettings: JsonWriterSettings = StoveMongoJsonWriterSettings.objectIdAsString,
  override val configureExposedConfiguration: (MongodbExposedConfiguration) -> List<String>
) : SystemOptions, ConfiguresExposedConfiguration<MongodbExposedConfiguration>

object StoveMongoJsonWriterSettings {
  val objectIdAsString: JsonWriterSettings = JsonWriterSettings.builder()
    .objectIdConverter { value, writer -> writer.writeString(value.toHexString()) }
    .build()
}
