package com.stove.spring.example.e2e.hierarchy

import com.trendyol.stove.http.*
import com.trendyol.stove.system.stove
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.string.shouldContain

class FunSpecContextHierarchyTest :
  FunSpec({

    context("index endpoint") {
      test("should return greeting") {
        stove {
          http {
            get<String>("/api/index") { actual ->
              actual shouldContain "Hi from Stove framework"
            }
          }
        }
      }

      test("should accept keyword parameter") {
        stove {
          http {
            get<String>("/api/index", queryParams = mapOf("keyword" to "ctx-test")) { actual ->
              actual shouldContain "ctx-test"
            }
          }
        }
      }
    }

    context("nested context levels") {
      context("level two") {
        test("should still work at deeper nesting") {
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

    test("flat top-level test") {
      stove {
        http {
          get<String>("/api/index") { actual ->
            actual shouldContain "Stove"
          }
        }
      }
    }
  })
