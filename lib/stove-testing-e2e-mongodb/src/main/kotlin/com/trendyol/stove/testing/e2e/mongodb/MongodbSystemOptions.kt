package com.trendyol.stove.testing.e2e.mongodb

import com.trendyol.stove.testing.e2e.serialization.StoveSerde
import com.trendyol.stove.testing.e2e.system.abstractions.*
import com.trendyol.stove.testing.e2e.system.annotations.StoveDsl
import org.bson.UuidRepresentation
import org.bson.codecs.configuration.CodecRegistry
import org.bson.codecs.pojo.PojoCodecProvider
import org.bson.internal.ProvidersCodecRegistry
import org.mongojack.JacksonCodecRegistry

@StoveDsl
data class MongodbSystemOptions(
  val databaseOptions: DatabaseOptions = DatabaseOptions(),
  val container: MongoContainerOptions = MongoContainerOptions(),
  override val configureExposedConfiguration: (MongodbExposedConfiguration) -> List<String>,
  val codecRegistry: CodecRegistry = JacksonCodecRegistry(
    StoveSerde.jackson.byConfiguring { addModule(ObjectIdModule()) },
    ProvidersCodecRegistry(listOf(PojoCodecProvider.builder().automatic(true).build())),
    UuidRepresentation.STANDARD
  )
) : SystemOptions, ConfiguresExposedConfiguration<MongodbExposedConfiguration>
