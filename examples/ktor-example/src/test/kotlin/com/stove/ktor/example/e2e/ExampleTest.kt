package com.stove.ktor.example.e2e

import arrow.core.*
import com.trendyol.stove.http.http
import com.trendyol.stove.kafka.kafka
import com.trendyol.stove.postgres.postgresql
import com.trendyol.stove.system.stove
import com.trendyol.stove.system.using
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import stove.ktor.example.application.*
import stove.ktor.example.domain.*
import kotlin.random.Random
import kotlin.time.Duration.Companion.seconds

class ExampleTest :
  FunSpec({
    data class ProductOfTest(
      val id: Long,
      val name: String
    )

    test("should save the product") {
      stove {
        val givenId = Random.nextInt()
        val givenName = "T-Shirt, Red, M"
        postgresql {
          shouldExecute(
            """
            DROP TABLE IF EXISTS Products;
            CREATE TABLE IF NOT EXISTS Products (
            	id serial PRIMARY KEY,
            	name VARCHAR (50)  NOT NULL
            );
            """.trimIndent()
          )
          shouldExecute("INSERT INTO Products (id, name) VALUES ('$givenId', 'T-Shirt, Red, S')")
        }
        http {
          postAndExpectBodilessResponse(
            "/products/$givenId",
            body = UpdateProductRequest(givenName).some(),
            token = None
          ) { actual ->
            actual.status shouldBe 200
          }
        }

        postgresql {
          shouldQuery<ProductOfTest>("Select * FROM Products WHERE id=$givenId", mapper = { row ->
            ProductOfTest(row.long("id"), row.string("name"))
          }) {
            it.count() shouldBe 1
            it.first().name shouldBe givenName
          }
        }

        using<ProductRepository> {
          this.findById(givenId) shouldBe Product(givenId, givenName)
        }

        kafka {
          shouldBePublished<DomainEvents.ProductUpdated>(5.seconds) {
            actual.id == givenId && actual.name == givenName
          }
          shouldBeConsumed<DomainEvents.ProductUpdated>(20.seconds) {
            actual.id == givenId && actual.name == givenName
          }
        }
      }
    }

    test("stove should be able to override the test deps") {
      stove {
        using<LockProvider> {
          (this is NoOpLockProvider) shouldBe true
        }
      }
    }
  })
