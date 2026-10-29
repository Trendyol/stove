package com.trendyol.stove.containers

import com.github.dockerjava.api.DockerClient
import com.github.dockerjava.api.command.ListContainersCmd
import com.github.dockerjava.api.model.Container
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.testcontainers.containers.GenericContainer
import org.testcontainers.utility.DockerImageName
import java.lang.reflect.Proxy

class ContainerIdentityTest : FunSpec({
  test("containers sharing an image resolve their own IDs without querying other containers") {
    val first = IdentityContainer("first")
    val second = IdentityContainer("second")

    first.containerIdAccess shouldBe "first"
    second.containerIdAccess shouldBe "second"
  }

  test("reuse resolves a single running container when the instance has no ID") {
    val container = reusedContainer(listOf(runningContainer("reused")))

    container.containerIdAccess shouldBe "reused"
  }

  test("reuse refuses to select another container when the image is ambiguous") {
    val container = reusedContainer(listOf(runningContainer("first"), runningContainer("second")))

    shouldThrow<IllegalStateException> { container.containerIdAccess }
      .message.shouldContain("Multiple running containers")
  }

  test("reuse reports a missing container instead of selecting a different image") {
    val container = reusedContainer(listOf(runningContainer("unrelated", "redis:7")))

    shouldThrow<IllegalStateException> { container.containerIdAccess }
      .message.shouldContain("not found")
  }
})

private val identityImage = DockerImageName.parse("postgres:17")

private class IdentityContainer(private val assignedId: String) : GenericContainer<IdentityContainer>(identityImage), StoveContainer {
  override val imageNameAccess: DockerImageName = identityImage
  override val dockerClientAccess: Lazy<DockerClient> = lazy { error("An assigned container ID must not require image lookup") }

  override fun getContainerId(): String = assignedId
}

private fun reusedContainer(containers: List<Container>): StoveContainer = object : StoveContainer {
  override val imageNameAccess: DockerImageName = identityImage
  override val dockerClientAccess: Lazy<DockerClient> = lazy {
    identityStub<DockerClient>("listContainersCmd") { identityStub<ListContainersCmd>("exec") { containers } }
  }
}

private fun runningContainer(containerId: String, imageName: String = identityImage.asCanonicalNameString()): Container = object : Container() {
  override fun getId(): String = containerId

  override fun getImage(): String = imageName
}

private inline fun <reified T> identityStub(method: String, crossinline result: () -> Any): T =
  Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, called, _ ->
    check(called.name == method) { "Unexpected Docker call: ${called.name}" }
    result()
  } as T
