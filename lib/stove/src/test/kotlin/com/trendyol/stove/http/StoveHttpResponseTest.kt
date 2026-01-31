package com.trendyol.stove.http

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

class StoveHttpResponseTest :
  FunSpec({

    context("Bodiless") {
      test("should store status and headers") {
        val response = StoveHttpResponse.Bodiless(
          status = 200,
          headers = mapOf("Content-Type" to "application/json")
        )

        response.status shouldBe 200
        response.headers shouldBe mapOf("Content-Type" to "application/json")
      }

      test("should handle empty headers") {
        val response = StoveHttpResponse.Bodiless(
          status = 404,
          headers = emptyMap()
        )

        response.status shouldBe 404
        response.headers shouldBe emptyMap()
      }

      test("should be instance of StoveHttpResponse") {
        val response = StoveHttpResponse.Bodiless(status = 200, headers = emptyMap())

        response.shouldBeInstanceOf<StoveHttpResponse>()
      }

      test("data class equality should work") {
        val response1 = StoveHttpResponse.Bodiless(status = 200, headers = mapOf("key" to "value"))
        val response2 = StoveHttpResponse.Bodiless(status = 200, headers = mapOf("key" to "value"))

        response1 shouldBe response2
      }

      test("copy should work") {
        val original = StoveHttpResponse.Bodiless(status = 200, headers = emptyMap())
        val copied = original.copy(status = 201)

        copied.status shouldBe 201
        copied.headers shouldBe emptyMap()
      }
    }

    context("WithBody") {
      test("should store status, headers, and body") {
        val response = StoveHttpResponse.WithBody(
          status = 200,
          headers = mapOf("Content-Type" to "application/json"),
          body = { "test body" }
        )

        response.status shouldBe 200
        response.headers shouldBe mapOf("Content-Type" to "application/json")
      }

      test("body should execute suspend function") {
        var executed = false
        val response = StoveHttpResponse.WithBody(
          status = 200,
          headers = emptyMap(),
          body = {
            executed = true
            "result"
          }
        )

        val result = response.body()

        executed shouldBe true
        result shouldBe "result"
      }

      test("should handle different body types") {
        data class User(
          val id: Int,
          val name: String
        )

        val response = StoveHttpResponse.WithBody(
          status = 200,
          headers = emptyMap(),
          body = { User(1, "John") }
        )

        val user = response.body()

        user.id shouldBe 1
        user.name shouldBe "John"
      }

      test("should be instance of StoveHttpResponse") {
        val response = StoveHttpResponse.WithBody(
          status = 200,
          headers = emptyMap(),
          body = { "body" }
        )

        response.shouldBeInstanceOf<StoveHttpResponse>()
      }

      test("should handle error status codes") {
        val response = StoveHttpResponse.WithBody(
          status = 500,
          headers = mapOf("X-Error" to "Internal Server Error"),
          body = { mapOf("error" to "Something went wrong") }
        )

        response.status shouldBe 500
        response.body() shouldBe mapOf("error" to "Something went wrong")
      }
    }

    context("sealed class behavior") {
      test("should pattern match on response type") {
        val bodiless: StoveHttpResponse = StoveHttpResponse.Bodiless(204, emptyMap())
        val withBody: StoveHttpResponse = StoveHttpResponse.WithBody(200, emptyMap()) { "body" }

        val bodilessResult = when (bodiless) {
          is StoveHttpResponse.Bodiless -> "no body"
          is StoveHttpResponse.WithBody<*> -> "has body"
        }

        val withBodyResult = when (withBody) {
          is StoveHttpResponse.Bodiless -> "no body"
          is StoveHttpResponse.WithBody<*> -> "has body"
        }

        bodilessResult shouldBe "no body"
        withBodyResult shouldBe "has body"
      }
    }
  })
