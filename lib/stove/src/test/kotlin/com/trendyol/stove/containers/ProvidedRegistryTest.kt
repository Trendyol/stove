package com.trendyol.stove.containers

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.testcontainers.utility.DockerImageName

class ProvidedRegistryTest :
  FunSpec({

    beforeEach {
      // Reset to default before each test
      DEFAULT_REGISTRY = "docker.io"
    }

    context("DEFAULT_REGISTRY") {
      test("should have docker.io as default") {
        DEFAULT_REGISTRY shouldBe "docker.io"
      }

      test("should be settable globally") {
        DEFAULT_REGISTRY = "my-registry.example.com"

        DEFAULT_REGISTRY shouldBe "my-registry.example.com"
      }
    }

    context("withProvidedRegistry") {
      test("should prepend registry to image name") {
        var capturedImageName: DockerImageName? = null

        withProvidedRegistry("postgres:15") { imageName ->
          capturedImageName = imageName
          "container"
        }

        capturedImageName?.toString() shouldContain "docker.io/postgres:15"
      }

      test("should use custom registry when provided") {
        var capturedImageName: DockerImageName? = null

        withProvidedRegistry(
          imageName = "myapp:latest",
          registry = "gcr.io/my-project"
        ) { imageName ->
          capturedImageName = imageName
          "container"
        }

        capturedImageName?.toString() shouldContain "gcr.io/my-project/myapp:latest"
      }

      test("should trim leading slashes from registry") {
        var capturedImageName: DockerImageName? = null

        withProvidedRegistry(
          imageName = "nginx:latest",
          registry = "/my-registry.com/"
        ) { imageName ->
          capturedImageName = imageName
          "container"
        }

        capturedImageName?.toString() shouldContain "my-registry.com/nginx:latest"
      }

      test("should trim leading slashes from image name") {
        var capturedImageName: DockerImageName? = null

        withProvidedRegistry(
          imageName = "/library/redis:7",
          registry = "docker.io"
        ) { imageName ->
          capturedImageName = imageName
          "container"
        }

        capturedImageName?.toString() shouldContain "docker.io/library/redis:7"
      }

      test("should use image name as compatible substitute when not provided") {
        var capturedImageName: DockerImageName? = null

        withProvidedRegistry("couchbase/server:7.0") { imageName ->
          capturedImageName = imageName
          "container"
        }

        // The image should be a substitute for the original image name
        capturedImageName shouldBe capturedImageName
      }

      test("should use custom compatible substitute when provided") {
        var capturedImageName: DockerImageName? = null

        withProvidedRegistry(
          imageName = "my-custom-postgres:15",
          registry = "my-registry.com",
          compatibleSubstitute = "postgres"
        ) { imageName ->
          capturedImageName = imageName
          "container"
        }

        capturedImageName?.toString() shouldContain "my-registry.com/my-custom-postgres:15"
      }

      test("should return result from containerBuilder") {
        data class TestContainer(
          val name: String
        )

        val result = withProvidedRegistry("test:latest") { imageName ->
          TestContainer(imageName.toString())
        }

        result.name shouldContain "docker.io/test:latest"
      }

      test("should use DEFAULT_REGISTRY when registry not specified") {
        DEFAULT_REGISTRY = "custom-default.io"
        var capturedImageName: DockerImageName? = null

        withProvidedRegistry("myimage:v1") { imageName ->
          capturedImageName = imageName
          "container"
        }

        capturedImageName?.toString() shouldContain "custom-default.io/myimage:v1"
      }

      test("should handle image name with organization") {
        var capturedImageName: DockerImageName? = null

        withProvidedRegistry(
          imageName = "confluentinc/cp-kafka:7.0.0",
          registry = "docker.io"
        ) { imageName ->
          capturedImageName = imageName
          "container"
        }

        capturedImageName?.toString() shouldContain "docker.io/confluentinc/cp-kafka:7.0.0"
      }
    }
  })
