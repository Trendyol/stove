package com.trendyol.stove.process

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.maps.shouldBeEmpty
import io.kotest.matchers.maps.shouldContainExactly
import io.kotest.matchers.shouldBe

class EnvProviderTest :
  FunSpec({
    context("EnvProvider.empty") {
      test("returns empty map") {
        val provider = EnvProvider.empty()
        provider.provide(mapOf("a" to "b")).shouldBeEmpty()
      }
    }

    context("EnvProvider fun interface") {
      test("can be implemented as lambda") {
        val provider = EnvProvider { configs ->
          mapOf("DB_HOST" to configs.getValue("database.host"))
        }

        provider.provide(mapOf("database.host" to "localhost")) shouldContainExactly
          mapOf("DB_HOST" to "localhost")
      }
    }

    context("envMapper builder") {
      test("maps config keys to env var names") {
        val provider = envMapper {
          "database.host" to "DB_HOST"
          "database.port" to "DB_PORT"
        }

        val result = provider.provide(
          mapOf("database.host" to "localhost", "database.port" to "5432")
        )

        result shouldContainExactly mapOf("DB_HOST" to "localhost", "DB_PORT" to "5432")
      }

      test("skips missing config keys silently") {
        val provider = envMapper {
          "database.host" to "DB_HOST"
          "database.port" to "DB_PORT"
        }

        val result = provider.provide(mapOf("database.host" to "localhost"))

        result shouldContainExactly mapOf("DB_HOST" to "localhost")
      }

      test("adds static env vars") {
        val provider = envMapper {
          env("APP_ENV", "test")
          env("LOG_LEVEL", "debug")
        }

        val result = provider.provide(emptyMap())

        result shouldContainExactly mapOf("APP_ENV" to "test", "LOG_LEVEL" to "debug")
      }

      test("adds computed env vars") {
        var counter = 0
        val provider = envMapper {
          env("COMPUTED") { "value-${++counter}" }
        }

        val result = provider.provide(emptyMap())

        result["COMPUTED"] shouldBe "value-1"
      }

      test("combines mappings and static vars") {
        val provider = envMapper {
          "database.host" to "DB_HOST"
          env("APP_ENV", "test")
          env("DYNAMIC") { "computed" }
        }

        val result = provider.provide(mapOf("database.host" to "localhost"))

        result shouldContainExactly mapOf(
          "DB_HOST" to "localhost",
          "APP_ENV" to "test",
          "DYNAMIC" to "computed"
        )
      }

      test("static vars override mappings with same name") {
        val provider = envMapper {
          "some.key" to "MY_VAR"
          env("MY_VAR", "override")
        }

        val result = provider.provide(mapOf("some.key" to "original"))

        result["MY_VAR"] shouldBe "override"
      }

      test("empty builder returns empty map") {
        val provider = envMapper { }
        provider.provide(mapOf("a" to "b")).shouldBeEmpty()
      }
    }
  })
