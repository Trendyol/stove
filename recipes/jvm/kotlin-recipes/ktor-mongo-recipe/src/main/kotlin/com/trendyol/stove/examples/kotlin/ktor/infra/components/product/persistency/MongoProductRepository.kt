package com.trendyol.stove.examples.kotlin.ktor.infra.components.product.persistency

import arrow.core.*
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.convertValue
import com.mongodb.client.model.Filters
import com.mongodb.kotlin.client.coroutine.MongoDatabase
import com.trendyol.stove.examples.domain.product.Product
import com.trendyol.stove.examples.kotlin.ktor.domain.product.ProductRepository
import com.trendyol.stove.examples.kotlin.ktor.infra.boilerplate.kafka.KafkaDomainEventPublisher
import kotlinx.coroutines.flow.firstOrNull
import org.bson.Document
import org.bson.json.JsonWriterSettings
import org.bson.types.ObjectId

class MongoProductRepository(
  mongo: MongoDatabase,
  private val objectMapper: ObjectMapper,
  private val eventPublisher: KafkaDomainEventPublisher
) : ProductRepository {
  private val collection = mongo.getCollection<Document>(PRODUCT_COLLECTION)

  override suspend fun save(product: Product) {
    val doc = Document(objectMapper.convertValue<MutableMap<String, Any>>(product))
    doc[RESERVED_ID] = ObjectId.get()
    collection.insertOne(doc)
    eventPublisher.publishFor(product)
  }

  override suspend fun findById(id: String): Option<Product> = collection
    .find(Filters.eq("id", id))
    .firstOrNull()
    ?.let { objectMapper.convertValue(it, Product::class.java) }
    .toOption()

  companion object {
    private const val RESERVED_ID = "_id"
    const val PRODUCT_COLLECTION = "products"
  }
}

object MongoJsonWriterSettings {
  val default: JsonWriterSettings = JsonWriterSettings
    .builder()
    .objectIdConverter { value, writer -> writer.writeString(value.toHexString()) }
    .build()
}
