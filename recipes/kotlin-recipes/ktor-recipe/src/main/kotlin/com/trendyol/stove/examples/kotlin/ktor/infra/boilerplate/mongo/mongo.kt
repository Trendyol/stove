package com.trendyol.stove.examples.kotlin.ktor.infra.boilerplate.mongo

import com.mongodb.*
import com.mongodb.kotlin.client.coroutine.*
import com.trendyol.stove.examples.kotlin.ktor.application.RecipeAppConfig
import org.bson.UuidRepresentation
import org.koin.core.KoinApplication
import org.koin.dsl.module

fun KoinApplication.configureMongo() {
  modules(createMongoModule())
}

private fun createMongoModule() = module {
  single { createMongoClient(get()) }
  single { createMongoDatabase(get(), get()) }
}

private fun createMongoClient(recipeAppConfig: RecipeAppConfig): MongoClient {
  return MongoClient.create(
    MongoClientSettings.builder()
      .uuidRepresentation(UuidRepresentation.STANDARD)
      .applyConnectionString(ConnectionString(recipeAppConfig.mongo.uri))
      .readConcern(ReadConcern.MAJORITY)
      .build()
  )
}

private fun createMongoDatabase(mongoClient: MongoClient, recipeAppConfig: RecipeAppConfig): MongoDatabase {
  return mongoClient.getDatabase(recipeAppConfig.mongo.database)
}
