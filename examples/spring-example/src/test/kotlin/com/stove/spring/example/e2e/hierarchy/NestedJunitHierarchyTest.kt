package com.stove.spring.example.e2e.hierarchy

import com.trendyol.stove.extensions.junit.StoveJUnitExtension
import com.trendyol.stove.http.*
import com.trendyol.stove.system.stove
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith

@ExtendWith(StoveJUnitExtension::class)
class NestedJunitHierarchyTest {

  @Nested
  inner class IndexEndpoint {

    @Test
    fun `should return greeting`() = runBlocking {
      stove {
        http {
          get<String>("/api/index") { actual ->
            actual shouldContain "Hi from Stove framework"
          }
        }
      }
    }

    @Test
    fun `should include keyword in response`() = runBlocking {
      stove {
        http {
          get<String>("/api/index", queryParams = mapOf("keyword" to "junit-nested")) { actual ->
            actual shouldContain "junit-nested"
          }
        }
      }
    }

    @Nested
    inner class WithQueryParams {

      @Test
      fun `should handle keyword parameter`() = runBlocking {
        stove {
          http {
            get<String>("/api/index", queryParams = mapOf("keyword" to "deep-nested")) { actual ->
              actual shouldContain "deep-nested"
            }
          }
        }
      }
    }
  }

  @Nested
  inner class HealthCheck {

    @Test
    fun `should be reachable`() = runBlocking {
      stove {
        http {
          get<String>("/api/index") { actual ->
            actual shouldContain "Stove"
          }
        }
      }
    }
  }

  @Test
  fun `flat junit test at root level`() = runBlocking {
    stove {
      http {
        get<String>("/api/index") { actual ->
          actual shouldContain "Stove"
        }
      }
    }
  }
}
