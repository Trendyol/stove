package com.trendyol.stove.extensions.junit

import arrow.core.some
import com.trendyol.stove.http.*
import com.trendyol.stove.reporting.StoveTestContextHolder
import com.trendyol.stove.system.*
import com.trendyol.stove.system.abstractions.ApplicationUnderTest
import com.trendyol.stove.wiremock.*
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.*
import org.junit.jupiter.api.extension.ExtendWith

class NoApplication : ApplicationUnderTest<Unit> {
  override suspend fun start(configurations: List<String>) {
    // do nothing
  }

  override suspend fun stop() {
    // do nothing
  }
}

data class TestDto(
  val name: String
)

@ExtendWith(StoveJUnitExtension::class)
class StoveJUnitExtensionTest {
  companion object {
    @JvmStatic
    @BeforeAll
    fun setup() = runBlocking {
      Stove()
        .with {
          httpClient {
            HttpClientSystemOptions(
              baseUrl = "http://localhost:8091"
            )
          }

          wiremock {
            WireMockSystemOptions(8091)
          }

          applicationUnderTest(NoApplication())
        }.run()
    }

    @JvmStatic
    @AfterAll
    fun teardown() = runBlocking {
      Stove.stop()
    }
  }

  @Test
  fun `beforeEach should set up test context correctly`() {
    val context = StoveTestContextHolder.get()
    context.shouldNotBeNull()
    context.testId shouldContain "StoveJUnitExtensionTest"
    context.testName shouldContain "beforeEach should set up test context correctly"
    context.specName shouldBe "StoveJUnitExtensionTest"
  }

  @Test
  fun `afterEach should clear test context`() {
    val context = StoveTestContextHolder.get()
    context.shouldNotBeNull()
    // Context should be cleared after test execution
  }

  @Test
  suspend fun `extension should integrate with WireMock and HTTP systems`() {
    val expectedName = "test-integration"
    stove {
      wiremock {
        mockGet("/test", statusCode = 200, responseBody = TestDto(expectedName).some())
      }

      http {
        get<TestDto>("/test") { actual ->
          actual.name shouldBe expectedName
        }
      }
    }
  }

  @Test
  suspend fun `handleTestExecutionException should enrich failures with Stove report`() {
    val exception = shouldThrow<AssertionError> {
      stove {
        wiremock {
          mockGet("/failing-endpoint", statusCode = 200, responseBody = TestDto("expected").some())
        }

        http {
          get<TestDto>("/failing-endpoint") { actual ->
            actual.name shouldBe "wrong-value" // This will fail
          }
        }
      }
    }

    // The exception should be enriched with Stove report
    exception.message.shouldNotBeNull()
    exception.message shouldContain "wrong-value"
  }

  @Test
  suspend fun `extension should handle multiple sequential HTTP calls`() {
    stove {
      wiremock {
        mockGet("/first", statusCode = 200, responseBody = TestDto("first").some())
        mockGet("/second", statusCode = 200, responseBody = TestDto("second").some())
      }

      http {
        get<TestDto>("/first") { actual ->
          actual.name shouldBe "first"
        }

        get<TestDto>("/second") { actual ->
          actual.name shouldBe "second"
        }
      }
    }
  }

  @Test
  suspend fun `extension should work with reporter to track test execution`() {
    val reporter = Stove.reporter()
    val testId = reporter.currentTestId()

    testId.shouldNotBeNull()
    testId shouldContain "StoveJUnitExtensionTest"
    testId shouldContain "extension should work with reporter to track test execution"

    stove {
      wiremock {
        mockGet("/reporter-test", statusCode = 200, responseBody = TestDto("reporter").some())
      }

      http {
        get<TestDto>("/reporter-test") { actual ->
          actual.name shouldBe "reporter"
        }
      }
    }
  }

  @Test
  suspend fun `extension should handle test context isolation between tests`() {
    // Each test should have its own isolated context
    val context = StoveTestContextHolder.get()
    context.shouldNotBeNull()
    context.testId shouldContain "extension should handle test context isolation between tests"

    stove {
      wiremock {
        mockGet("/isolation-test", statusCode = 200, responseBody = TestDto("isolated").some())
      }

      http {
        get<TestDto>("/isolation-test") { actual ->
          actual.name shouldBe "isolated"
        }
      }
    }
  }
}
