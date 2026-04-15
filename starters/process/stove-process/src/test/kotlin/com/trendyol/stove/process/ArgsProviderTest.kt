package com.trendyol.stove.process

import com.trendyol.stove.system.ReadinessStrategy
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import kotlinx.coroutines.runBlocking
import kotlin.time.Duration.Companion.milliseconds

class ArgsProviderTest :
  FunSpec({
    context("ArgsProvider.empty") {
      test("returns empty list") {
        val provider = ArgsProvider.empty()
        provider.provide(mapOf("a" to "b")).shouldBeEmpty()
      }
    }

    context("ArgsProvider fun interface") {
      test("can be implemented as lambda") {
        val provider = ArgsProvider { configs ->
          listOf("--host", configs.getValue("database.host"))
        }

        provider.provide(mapOf("database.host" to "localhost")) shouldContainExactly
          listOf("--host", "localhost")
      }
    }

    context("argsMapper with equals separator") {
      test("produces --flag=value args") {
        val provider = argsMapper(prefix = "--", separator = "=") {
          "database.host" to "db-host"
          "database.port" to "db-port"
        }

        val result = provider.provide(
          mapOf("database.host" to "localhost", "database.port" to "5432")
        )

        result shouldContainExactly listOf("--db-host=localhost", "--db-port=5432")
      }
    }

    context("argsMapper with space separator") {
      test("produces two separate args per mapping") {
        val provider = argsMapper(prefix = "--", separator = " ") {
          "database.host" to "db-host"
          "database.port" to "db-port"
        }

        val result = provider.provide(
          mapOf("database.host" to "localhost", "database.port" to "5432")
        )

        result shouldContainExactly listOf("--db-host", "localhost", "--db-port", "5432")
      }
    }

    context("argsMapper with single-dash prefix") {
      test("produces -flag value args") {
        val provider = argsMapper(prefix = "-", separator = " ") {
          "database.host" to "h"
          "database.port" to "p"
        }

        val result = provider.provide(
          mapOf("database.host" to "localhost", "database.port" to "5432")
        )

        result shouldContainExactly listOf("-h", "localhost", "-p", "5432")
      }
    }

    context("argsMapper with no prefix") {
      test("produces flag=value args") {
        val provider = argsMapper(prefix = "", separator = "=") {
          "database.host" to "db-host"
        }

        val result = provider.provide(mapOf("database.host" to "localhost"))

        result shouldContainExactly listOf("db-host=localhost")
      }
    }

    context("argsMapper skips missing keys") {
      test("only includes present config keys") {
        val provider = argsMapper(prefix = "--", separator = "=") {
          "database.host" to "db-host"
          "database.port" to "db-port"
        }

        val result = provider.provide(mapOf("database.host" to "localhost"))

        result shouldContainExactly listOf("--db-host=localhost")
      }
    }

    context("argsMapper static args") {
      test("adds boolean flag without value") {
        val provider = argsMapper(prefix = "--", separator = "=") {
          arg("verbose")
        }

        val result = provider.provide(emptyMap())

        result shouldContainExactly listOf("--verbose")
      }

      test("adds flag with static value") {
        val provider = argsMapper(prefix = "--", separator = "=") {
          arg("log-level", "debug")
        }

        val result = provider.provide(emptyMap())

        result shouldContainExactly listOf("--log-level=debug")
      }

      test("adds flag with computed value") {
        val provider = argsMapper(prefix = "--", separator = "=") {
          arg("config-file") { "/tmp/test.yaml" }
        }

        val result = provider.provide(emptyMap())

        result shouldContainExactly listOf("--config-file=/tmp/test.yaml")
      }

      test("static flag with space separator produces two args") {
        val provider = argsMapper(prefix = "--", separator = " ") {
          arg("log-level", "debug")
        }

        val result = provider.provide(emptyMap())

        result shouldContainExactly listOf("--log-level", "debug")
      }
    }

    context("argsMapper combines mappings and static args") {
      test("mappings come before static args") {
        val provider = argsMapper(prefix = "--", separator = "=") {
          "database.host" to "db-host"
          arg("verbose")
          arg("log-level", "debug")
        }

        val result = provider.provide(mapOf("database.host" to "localhost"))

        result shouldContainExactly listOf("--db-host=localhost", "--verbose", "--log-level=debug")
      }
    }

    context("empty builder") {
      test("returns empty list") {
        val provider = argsMapper { }
        provider.provide(mapOf("a" to "b")).shouldBeEmpty()
      }
    }

    context("integration with ProcessApplicationUnderTest") {
      test("CLI args are appended to command") {
        val aut = ProcessApplicationUnderTest(
          ProcessApplicationOptions(
            command = listOf("sh", "-c", "echo received: \"$@\"", "--"),
            target = ProcessTarget.Worker(
              readiness = ReadinessStrategy.FixedDelay(100.milliseconds)
            ),
            argsProvider = argsMapper(prefix = "--", separator = "=") {
              "database.host" to "db-host"
              arg("verbose")
            }
          )
        )

        runBlocking {
          aut.start(listOf("database.host=localhost"))
          aut.stop()
        }
      }

      test("both env vars and CLI args work together") {
        val aut = ProcessApplicationUnderTest(
          ProcessApplicationOptions(
            command = listOf(
              "sh",
              "-c",
              "[ \"\$DB_HOST\" = 'localhost' ] || exit 1; sleep 1"
            ),
            target = ProcessTarget.Worker(
              readiness = ReadinessStrategy.FixedDelay(100.milliseconds)
            ),
            envProvider = envMapper {
              "database.host" to "DB_HOST"
            },
            argsProvider = argsMapper(prefix = "--", separator = "=") {
              "database.port" to "db-port"
            }
          )
        )

        runBlocking {
          aut.start(listOf("database.host=localhost", "database.port=5432"))
          aut.stop()
        }
      }
    }
  })
