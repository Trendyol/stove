package com.trendyol.stove.testing.e2e.couchbase

import com.couchbase.client.core.error.DocumentNotFoundException
import com.trendyol.stove.testing.e2e.couchbase.CouchbaseSystem.Companion.bucket
import com.trendyol.stove.testing.e2e.system.TestSystem.Companion.validate
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.assertThrows
import java.util.*

/**
 * Couchbase system tests that run against both container-based and provided instances.
 *
 * These tests verify:
 * - Basic CRUD operations work correctly
 * - Migrations are executed properly (creating collections)
 * - The same test code works for both container and provided modes
 *
 * To run with provided instance mode:
 * ```
 * ./gradlew :lib:stove-testing-e2e-couchbase:test -DuseProvided=true
 * ```
 */
class CouchbaseTestSystemUsesDslTests :
  FunSpec({

    data class ExampleInstance(
      val id: String,
      val description: String
    )

    test("migration should create 'another' collection") {
      val id = UUID.randomUUID().toString()
      val anotherCollectionName = "another"
      validate {
        couchbase {
          // This test verifies that the migration created the 'another' collection
          save(anotherCollectionName, id = id, ExampleInstance(id = id, description = "migration test"))
          shouldGet<ExampleInstance>(anotherCollectionName, id) { actual ->
            actual.id shouldBe id
            actual.description shouldBe "migration test"
          }
          shouldDelete(anotherCollectionName, id)
        }
      }
    }

    test("should save and get") {
      val id = UUID.randomUUID().toString()
      val anotherCollectionName = "another"
      validate {
        couchbase {
          saveToDefaultCollection(id, ExampleInstance(id = id, description = testCase.name.name))
          save(anotherCollectionName, id = id, ExampleInstance(id = id, description = testCase.name.name))
          shouldGet<ExampleInstance>(id) { actual ->
            actual.id shouldBe id
            actual.description shouldBe testCase.name.name
          }
          shouldGet<ExampleInstance>(anotherCollectionName, id) { actual ->
            actual.id shouldBe id
            actual.description shouldBe testCase.name.name
          }
        }
      }
    }

    test("should not get when document does not exist") {
      val id = UUID.randomUUID().toString()
      val notExistDocId = UUID.randomUUID().toString()
      validate {
        couchbase {
          saveToDefaultCollection(id, ExampleInstance(id = id, description = testCase.name.name))
          shouldGet<ExampleInstance>(id) { actual ->
            actual.id shouldBe id
            actual.description shouldBe testCase.name.name
          }
          shouldNotExist(notExistDocId)
        }
      }
    }

    test("should throw assertion exception when document exist") {
      val id = UUID.randomUUID().toString()
      validate {
        couchbase {
          saveToDefaultCollection(id, ExampleInstance(id = id, description = testCase.name.name))
          shouldGet<ExampleInstance>(id) { actual ->
            actual.id shouldBe id
            actual.description shouldBe testCase.name.name
          }
          assertThrows<AssertionError> { shouldNotExist(id) }
        }
      }
    }

    test("should delete") {
      val id = UUID.randomUUID().toString()
      validate {
        couchbase {
          saveToDefaultCollection(id, ExampleInstance(id = id, description = testCase.name.name))
          shouldGet<ExampleInstance>(id) { actual ->
            actual.id shouldBe id
            actual.description shouldBe testCase.name.name
          }
          shouldDelete(id)
          shouldNotExist(id)
        }
      }
    }

    test("should delete from another collection") {
      val id = UUID.randomUUID().toString()
      val anotherCollectionName = "another"
      validate {
        couchbase {
          save(anotherCollectionName, id = id, ExampleInstance(id = id, description = testCase.name.name))
          shouldGet<ExampleInstance>(anotherCollectionName, id) { actual ->
            actual.id shouldBe id
            actual.description shouldBe testCase.name.name
          }
          shouldDelete(anotherCollectionName, id)
          shouldNotExist(anotherCollectionName, id)
        }
      }
    }

    test("should not delete when document does not exist") {
      val id = UUID.randomUUID().toString()
      validate {
        couchbase {
          shouldNotExist(id)
          assertThrows<DocumentNotFoundException> { shouldDelete(id) }
        }
      }
    }

    test("should not delete from another collection when document does not exist") {
      val id = UUID.randomUUID().toString()
      val anotherCollectionName = "another"
      validate {
        couchbase {
          shouldNotExist(anotherCollectionName, id)
          assertThrows<DocumentNotFoundException> { shouldDelete(anotherCollectionName, id) }
        }
      }
    }

    test("should query") {
      val id = UUID.randomUUID().toString()
      val id2 = UUID.randomUUID().toString()
      validate {
        couchbase {
          saveToDefaultCollection(id, ExampleInstance(id = id, description = testCase.name.name))
          saveToDefaultCollection(id2, ExampleInstance(id = id2, description = testCase.name.name))
          shouldQuery<ExampleInstance>(
            "SELECT c.id, c.* FROM `${this.bucket().name}`.`${this.collection.scope.name}`.`${this.collection.name}` c"
          ) { result ->
            result.size shouldBeGreaterThanOrEqual 2
            result.contains(ExampleInstance(id = id, description = testCase.name.name)) shouldBe true
            result.contains(ExampleInstance(id = id2, description = testCase.name.name)) shouldBe true
          }
        }
      }
    }
  })
