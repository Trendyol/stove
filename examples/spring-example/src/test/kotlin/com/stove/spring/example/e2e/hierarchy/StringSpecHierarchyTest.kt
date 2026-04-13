package com.stove.spring.example.e2e.hierarchy

import com.trendyol.stove.http.*
import com.trendyol.stove.system.stove
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.string.shouldContain

class StringSpecHierarchyTest :
  StringSpec({

    "should return greeting" {
      stove {
        http {
          get<String>("/api/index") { actual ->
            actual shouldContain "Hi from Stove framework"
          }
        }
      }
    }

    "should include keyword in response" {
      stove {
        http {
          get<String>("/api/index", queryParams = mapOf("keyword" to "string-spec")) { actual ->
            actual shouldContain "string-spec"
          }
        }
      }
    }

    "should contain framework name" {
      stove {
        http {
          get<String>("/api/index") { actual ->
            actual shouldContain "Stove"
          }
        }
      }
    }
  })
