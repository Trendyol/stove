package com.trendyol.stove.examples.kotlin.ktor.infra.components.product.persistency

import org.jetbrains.exposed.v1.core.Table

/**
 * [com.trendyol.stove.examples.domain.product.Product]
 */
object ProductTable : Table("products") {
  val id = text("id")
  val name = text("name")
  val price = double("price")
  val categoryId = integer("category_id")
  val version = long("version")

  override val primaryKey = PrimaryKey(id)
}
