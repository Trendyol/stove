package com.trendyol.stove

import com.trendyol.stove.spring.SpringApplicationUnderTest
import com.trendyol.stove.system.Stove
import com.trendyol.stove.system.stove
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import org.mockito.kotlin.*
import org.springframework.context.ConfigurableApplicationContext

class SpringApplicationUnderTestTests :
  FunSpec({

    test("should include default test-system configuration") {
      val testSystem = Stove()
      var capturedArgs: Array<String> = emptyArray()

      val runner: (Array<String>) -> ConfigurableApplicationContext = { args ->
        capturedArgs = args
        mock<ConfigurableApplicationContext> {
          on { isRunning } doReturn true
          on { isActive } doReturn true
        }
      }

      val applicationUnderTest = SpringApplicationUnderTest(
        stove = testSystem,
        runner = runner,
        parameters = listOf()
      )

      applicationUnderTest.start(listOf())

      capturedArgs.toList().shouldContain("--test-system=true")
    }

    test("should include custom parameters") {
      val testSystem = Stove()
      var capturedArgs: Array<String> = emptyArray()

      val runner: (Array<String>) -> ConfigurableApplicationContext = { args ->
        capturedArgs = args
        mock<ConfigurableApplicationContext> {
          on { isRunning } doReturn true
          on { isActive } doReturn true
        }
      }

      val applicationUnderTest = SpringApplicationUnderTest(
        stove = testSystem,
        runner = runner,
        parameters = listOf("custom.param=value")
      )

      applicationUnderTest.start(listOf())

      capturedArgs.toList().shouldContain("--custom.param=value")
    }

    test("should include provided configurations") {
      val testSystem = Stove()
      var capturedArgs: Array<String> = emptyArray()

      val runner: (Array<String>) -> ConfigurableApplicationContext = { args ->
        capturedArgs = args
        mock<ConfigurableApplicationContext> {
          on { isRunning } doReturn true
          on { isActive } doReturn true
        }
      }

      val applicationUnderTest = SpringApplicationUnderTest(
        stove = testSystem,
        runner = runner,
        parameters = listOf()
      )

      applicationUnderTest.start(listOf("server.port=8080", "spring.profiles.active=test"))

      capturedArgs.toList().shouldContain("--server.port=8080")
      capturedArgs.toList().shouldContain("--spring.profiles.active=test")
    }

    test("should combine all configurations with -- prefix") {
      val testSystem = Stove()
      var capturedArgs: Array<String> = emptyArray()

      val runner: (Array<String>) -> ConfigurableApplicationContext = { args ->
        capturedArgs = args
        mock<ConfigurableApplicationContext> {
          on { isRunning } doReturn true
          on { isActive } doReturn true
        }
      }

      val applicationUnderTest = SpringApplicationUnderTest(
        stove = testSystem,
        runner = runner,
        parameters = listOf("param1=val1")
      )

      applicationUnderTest.start(listOf("config1=val1"))

      capturedArgs.all { it.startsWith("--") } shouldBe true
    }

    test("should stop application context") {
      val mockContext = mock<ConfigurableApplicationContext> {
        on { isRunning } doReturn true
        on { isActive } doReturn true
      }
      val testSystem = Stove()

      val runner: (Array<String>) -> ConfigurableApplicationContext = { mockContext }

      val applicationUnderTest = SpringApplicationUnderTest(
        stove = testSystem,
        runner = runner,
        parameters = listOf()
      )

      applicationUnderTest.start(listOf())
      applicationUnderTest.stop()

      verify(mockContext).stop()
    }
  })
