package com.trendyol.stove.system.abstractions

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Unit tests for the ProvidedSystemOptions interface.
 */
class ProvidedSystemOptionsTest :
  FunSpec({

    /**
     * Test exposed configuration.
     */
    data class TestExposedConfiguration(
      val host: String,
      val port: Int
    ) : ExposedConfiguration

    /**
     * Base system options (container mode).
     */
    open class TestSystemOptions(
      val name: String,
      override val configureExposedConfiguration: (TestExposedConfiguration) -> List<String>
    ) : SystemOptions,
      ConfiguresExposedConfiguration<TestExposedConfiguration>

    /**
     * Provided system options (external instance mode).
     */
    class ProvidedTestSystemOptions(
      override val providedConfig: TestExposedConfiguration,
      override val runMigrationsForProvided: Boolean = true,
      name: String = "provided",
      configureExposedConfiguration: (TestExposedConfiguration) -> List<String>
    ) : TestSystemOptions(name, configureExposedConfiguration),
      ProvidedSystemOptions<TestExposedConfiguration>

    test("ProvidedSystemOptions instance check should work with base type reference") {
      val providedOptions: TestSystemOptions = ProvidedTestSystemOptions(
        providedConfig = TestExposedConfiguration("localhost", 8080),
        runMigrationsForProvided = true,
        configureExposedConfiguration = { listOf() }
      )

      // When referenced through base type, instance check is meaningful
      (providedOptions is ProvidedSystemOptions<*>) shouldBe true
    }

    test("providedConfig should hold the configuration") {
      val config = TestExposedConfiguration("external-host", 9090)
      val providedOptions = ProvidedTestSystemOptions(
        providedConfig = config,
        configureExposedConfiguration = { listOf() }
      )

      providedOptions.providedConfig shouldBe config
      providedOptions.providedConfig.host shouldBe "external-host"
      providedOptions.providedConfig.port shouldBe 9090
    }

    test("runMigrationsForProvided should default to true") {
      val providedOptions = ProvidedTestSystemOptions(
        providedConfig = TestExposedConfiguration("localhost", 8080),
        configureExposedConfiguration = { listOf() }
      )

      providedOptions.runMigrationsForProvided shouldBe true
    }

    test("runMigrationsForProvided can be set to false") {
      val providedOptions = ProvidedTestSystemOptions(
        providedConfig = TestExposedConfiguration("localhost", 8080),
        runMigrationsForProvided = false,
        configureExposedConfiguration = { listOf() }
      )

      providedOptions.runMigrationsForProvided shouldBe false
    }

    test("base options should not be ProvidedSystemOptions") {
      val baseOptions = TestSystemOptions(
        name = "base",
        configureExposedConfiguration = { listOf() }
      )

      // Base options is not a ProvidedSystemOptions
      (baseOptions is ProvidedSystemOptions<*>) shouldBe false
    }

    test("provided options should inherit from base options") {
      val providedOptions = ProvidedTestSystemOptions(
        providedConfig = TestExposedConfiguration("localhost", 8080),
        name = "inherited-name",
        configureExposedConfiguration = { cfg -> listOf("host=${cfg.host}", "port=${cfg.port}") }
      )

      // Should have base class properties
      providedOptions.name shouldBe "inherited-name"

      // Should produce correct configuration
      val config = providedOptions.configureExposedConfiguration(providedOptions.providedConfig)
      config shouldBe listOf("host=localhost", "port=8080")
    }

    test("type checking can distinguish between base and provided options") {
      val baseOptions: TestSystemOptions = TestSystemOptions(
        name = "base",
        configureExposedConfiguration = { listOf() }
      )

      val providedOptions: TestSystemOptions = ProvidedTestSystemOptions(
        providedConfig = TestExposedConfiguration("localhost", 8080),
        configureExposedConfiguration = { listOf() }
      )

      // Using when expression to distinguish
      fun getMode(options: TestSystemOptions): String = when (options) {
        is ProvidedTestSystemOptions -> "provided"
        else -> "container"
      }

      getMode(baseOptions) shouldBe "container"
      getMode(providedOptions) shouldBe "provided"
    }
  })
