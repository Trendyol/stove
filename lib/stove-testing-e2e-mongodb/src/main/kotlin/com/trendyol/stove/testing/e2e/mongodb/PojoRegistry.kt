package com.trendyol.stove.testing.e2e.mongodb

import com.mongodb.reactivestreams.client.MongoClients.getDefaultCodecRegistry
import org.bson.codecs.configuration.CodecRegistries.fromProviders
import org.bson.codecs.configuration.CodecRegistries.fromRegistries
import org.bson.codecs.configuration.CodecRegistry
import org.bson.codecs.pojo.Conventions
import org.bson.codecs.pojo.PojoCodecProvider
import kotlin.reflect.KClass

data class PojoRegistry(
    val registry: CodecRegistry = fromRegistries()
) {

    private var builder: PojoCodecProvider.Builder = PojoCodecProvider.builder().conventions(
        Conventions.DEFAULT_CONVENTIONS
    )

    inline fun <reified T : Any> register(): PojoRegistry = register(T::class)

    fun <T : Any> register(clazz: KClass<T>): PojoRegistry = builder.register(clazz.java).let { this }

    fun build(): CodecRegistry = fromRegistries(registry, getDefaultCodecRegistry(), fromProviders(builder.build()))
}
