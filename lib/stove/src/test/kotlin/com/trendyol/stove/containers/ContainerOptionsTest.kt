package com.trendyol.stove.containers

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.testcontainers.utility.DockerImageName

class ContainerOptionsTest :
  FunSpec({
    test("imageWithTag should combine image and tag") {
      val options = object : ContainerOptions<StoveContainer> {
        override val registry: String = "docker.io"
        override val image: String = "alpine"
        override val tag: String = "3.19"
        override val compatibleSubstitute: String? = null
        override val useContainerFn: UseContainerFn<StoveContainer> = { _ -> error("unused") }
        override val containerFn: ContainerFn<StoveContainer> = { }
      }

      options.imageWithTag shouldBe "alpine:3.19"
    }

    test("useContainerFn should receive docker image name") {
      var received: DockerImageName? = null
      val options = object : ContainerOptions<StoveContainer> {
        override val registry: String = "docker.io"
        override val image: String = "alpine"
        override val tag: String = "3.19"
        override val compatibleSubstitute: String? = null
        override val useContainerFn: UseContainerFn<StoveContainer> = { imageName ->
          received = imageName
          error("stop")
        }
        override val containerFn: ContainerFn<StoveContainer> = { }
      }

      try {
        options.useContainerFn(DockerImageName.parse(options.imageWithTag))
      } catch (_: Throwable) {
      }

      received?.asCanonicalNameString() shouldBe "alpine:3.19"
    }
  })
