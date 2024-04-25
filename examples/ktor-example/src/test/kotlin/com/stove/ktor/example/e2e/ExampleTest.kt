package com.stove.ktor.example.e2e

import arrow.core.*
import com.trendol.stove.testing.e2e.rdbms.postgres.postgresql
import com.trendyol.stove.testing.e2e.http.http
import com.trendyol.stove.testing.e2e.system.TestSystem.Companion.validate
import com.trendyol.stove.testing.e2e.system.using
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import stove.ktor.example.application.*
import stove.ktor.example.domain.*

class ExampleTest : FunSpec({
  data class ProductOfTest(
    val id: Long,
    val name: String
  )

  test("should save the product") {
    validate {
      val givenId = 10L
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
        shouldQuery<ProductOfTest>("Select * FROM Products WHERE id=$givenId") {
          it.count() shouldBe 1
          it.first().name shouldBe givenName
        }
      }

      using<ProductRepository> {
        this.findById(givenId) shouldBe Product(givenId, givenName)
      }
    }
  }

  test("stove should be able to override the test deps") {
    validate {
      using<LockProvider> {
        (this is NoOpLockProvider) shouldBe true
      }
    }
  }
})
