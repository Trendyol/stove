package com.trendyol.stove.testing.e2e

import com.fasterxml.jackson.databind.ObjectMapper
import com.trendyol.stove.testing.e2e.system.TestSystem.Companion.validate
import com.trendyol.stove.testing.e2e.system.using
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.seconds

/**
 * Shared bridge system tests that work across all Spring Boot versions.
 * Each version module should create their own test class that extends this.
 */
abstract class BridgeSystemTests :
  ShouldSpec({
    should("bridge to application") {
      validate {
        using<ExampleService> {
          whatIsTheTime() shouldBe GetUtcNow.frozenTime
        }

        using<ParameterCollectorOfSpringBoot> {
          parameters shouldBe listOf(
            "--test-system=true",
            "--context=SetupOfBridgeSystemTests"
          )
        }

        delay(5.seconds)
        using<TestAppInitializers> {
          appReady shouldBe true
          onEvent shouldBe true
        }
      }
    }

    should("resolve multiple") {
      validate {
        using<GetUtcNow, TestAppInitializers> { getUtcNow: GetUtcNow, testAppInitializers: TestAppInitializers ->
          getUtcNow() shouldBe GetUtcNow.frozenTime
          testAppInitializers.appReady shouldBe true
          testAppInitializers.onEvent shouldBe true
        }

        using<GetUtcNow, TestAppInitializers, ParameterCollectorOfSpringBoot> {
          getUtcNow: GetUtcNow,
          testAppInitializers: TestAppInitializers,
          parameterCollectorOfSpringBoot: ParameterCollectorOfSpringBoot
          ->
          getUtcNow() shouldBe GetUtcNow.frozenTime
          testAppInitializers.appReady shouldBe true
          testAppInitializers.onEvent shouldBe true
          parameterCollectorOfSpringBoot.parameters shouldBe listOf(
            "--test-system=true",
            "--context=SetupOfBridgeSystemTests"
          )
        }

        using<GetUtcNow, TestAppInitializers, ParameterCollectorOfSpringBoot, ExampleService> {
          getUtcNow: GetUtcNow,
          testAppInitializers: TestAppInitializers,
          parameterCollectorOfSpringBoot: ParameterCollectorOfSpringBoot,
          exampleService: ExampleService
          ->
          getUtcNow() shouldBe GetUtcNow.frozenTime
          testAppInitializers.appReady shouldBe true
          testAppInitializers.onEvent shouldBe true
          parameterCollectorOfSpringBoot.parameters shouldBe listOf(
            "--test-system=true",
            "--context=SetupOfBridgeSystemTests"
          )
          exampleService.whatIsTheTime() shouldBe GetUtcNow.frozenTime
        }

        using<GetUtcNow, TestAppInitializers, ParameterCollectorOfSpringBoot, ExampleService, ObjectMapper> {
          getUtcNow: GetUtcNow,
          testAppInitializers: TestAppInitializers,
          parameterCollectorOfSpringBoot: ParameterCollectorOfSpringBoot,
          exampleService: ExampleService,
          objectMapper: ObjectMapper
          ->
          getUtcNow() shouldBe GetUtcNow.frozenTime
          testAppInitializers.appReady shouldBe true
          testAppInitializers.onEvent shouldBe true
          parameterCollectorOfSpringBoot.parameters shouldBe listOf(
            "--test-system=true",
            "--context=SetupOfBridgeSystemTests"
          )
          exampleService.whatIsTheTime() shouldBe GetUtcNow.frozenTime
          objectMapper.writeValueAsString(mapOf("a" to "b")) shouldBe """{"a":"b"}"""
        }
      }
    }
  })
