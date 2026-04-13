package com.stove.spring.example.e2e.hierarchy

import com.trendyol.stove.http.*
import com.trendyol.stove.system.stove
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.string.shouldContain

class BehaviorSpecHierarchyTest :
  BehaviorSpec({

    given("the index endpoint") {
      `when`("requesting with a keyword") {
        then("should return greeting with keyword") {
          stove {
            http {
              get<String>("/api/index", queryParams = mapOf("keyword" to "bdd-test")) { actual ->
                actual shouldContain "Hi from Stove framework with bdd-test"
              }
            }
          }
        }

        then("should contain framework name") {
          stove {
            http {
              get<String>("/api/index", queryParams = mapOf("keyword" to "bdd")) { actual ->
                actual shouldContain "Stove"
              }
            }
          }
        }
      }

      `when`("requesting without a keyword") {
        then("should return default greeting") {
          stove {
            http {
              get<String>("/api/index") { actual ->
                actual shouldContain "Hi from Stove framework"
              }
            }
          }
        }
      }
    }

    given("health check scenarios") {
      `when`("the application is running") {
        then("index should be reachable") {
          stove {
            http {
              get<String>("/api/index") { actual ->
                actual shouldContain "Stove"
              }
            }
          }
        }
      }
    }
  })
