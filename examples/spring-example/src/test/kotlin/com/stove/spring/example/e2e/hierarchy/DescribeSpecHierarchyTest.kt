package com.stove.spring.example.e2e.hierarchy

import com.trendyol.stove.http.*
import com.trendyol.stove.system.stove
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.string.shouldContain

class DescribeSpecHierarchyTest :
  DescribeSpec({

    describe("Index API") {
      it("should return greeting") {
        stove {
          http {
            get<String>("/api/index") { actual ->
              actual shouldContain "Hi from Stove framework"
            }
          }
        }
      }

      describe("with query parameters") {
        it("should include keyword in response") {
          stove {
            http {
              get<String>("/api/index", queryParams = mapOf("keyword" to "describe-test")) { actual ->
                actual shouldContain "describe-test"
              }
            }
          }
        }

        it("should handle different keywords") {
          stove {
            http {
              get<String>("/api/index", queryParams = mapOf("keyword" to "another")) { actual ->
                actual shouldContain "another"
              }
            }
          }
        }
      }
    }

    describe("Application health") {
      it("should respond to index") {
        stove {
          http {
            get<String>("/api/index") { actual ->
              actual shouldContain "Stove"
            }
          }
        }
      }
    }
  })
