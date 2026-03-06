package stove.quarkus.example.infrastructure.kafka

import io.quarkus.kafka.client.serialization.ObjectMapperDeserializer
import io.quarkus.kafka.client.serialization.ObjectMapperSerializer
import stove.quarkus.example.application.CreateProductCommand
import stove.quarkus.example.application.ProductCreatedEvent

class CreateProductCommandDeserializer : ObjectMapperDeserializer<CreateProductCommand>(CreateProductCommand::class.java)

class ProductCreatedEventSerializer : ObjectMapperSerializer<ProductCreatedEvent>()
