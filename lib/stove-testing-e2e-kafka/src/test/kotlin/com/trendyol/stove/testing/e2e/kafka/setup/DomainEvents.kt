package com.trendyol.stove.testing.e2e.kafka.setup

object DomainEvents {
    data class ProductCreated(val productId: String)
    data class ProductFailingCreated(val productId: String)
    data class BacklogCreated(val backlogId: String)
}
