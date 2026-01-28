package stove.spring.standalone.example.domain

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.javatime.timestamp

object Products : Table("products") {
  val id = long("id")
  val name = varchar("name", 255)
  val supplierId = long("supplier_id")
  val createdDate = timestamp("created_date")

  override val primaryKey = PrimaryKey(id)
}
