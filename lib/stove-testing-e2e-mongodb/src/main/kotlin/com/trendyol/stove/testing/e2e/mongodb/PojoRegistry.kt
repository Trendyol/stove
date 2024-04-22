package com.trendyol.stove.testing.e2e.mongodb

import com.mongodb.reactivestreams.client.MongoClients.getDefaultCodecRegistry
import com.trendyol.stove.testing.e2e.system.annotations.StoveDsl
import org.bson.codecs.configuration.CodecRegistries.*
import org.bson.codecs.configuration.CodecRegistry
import org.bson.codecs.pojo.*
import kotlin.reflect.KClass

@StoveDsl
data class PojoRegistry(
  val registry: CodecRegistry = fromRegistries()
) {
  private var builder: PojoCodecProvider.Builder =
    PojoCodecProvider.builder().conventions(
      Conventions.DEFAULT_CONVENTIONS
    )

  inline fun <reified T : Any> register(): PojoRegistry = register(T::class)

  fun <T : Any> register(clazz: KClass<T>): PojoRegistry = builder.register(clazz.java).let { this }

  fun build(): CodecRegistry = fromRegistries(registry, getDefaultCodecRegistry(), fromProviders(builder.build()))
}
