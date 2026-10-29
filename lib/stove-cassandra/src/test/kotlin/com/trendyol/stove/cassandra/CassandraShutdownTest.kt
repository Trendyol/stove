package com.trendyol.stove.cassandra

import com.datastax.oss.driver.api.core.CqlSession
import com.trendyol.stove.system.Stove
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.testcontainers.utility.DockerImageName
import java.io.Closeable
import java.lang.reflect.Proxy

class CassandraShutdownTest : FunSpec({
  test("cleanup and session failures still stop the container without escaping close") {
    CassandraShutdownFixture().use { fixture ->
      fixture.system.close()
      fixture.events shouldBe listOf("cleanup", "session.close", "container.stop")
      fixture.cleanupFailure.suppressed.toList() shouldBe listOf(fixture.closeFailure)
    }
  }

  test("closing before session initialization still stops the container") {
    CassandraShutdownFixture(initialize = false).use { fixture ->
      fixture.system.close()
      fixture.events shouldBe listOf("container.stop")
    }
  }
})

private class CassandraShutdownFixture(initialize: Boolean = true) : Closeable {
  val events = mutableListOf<String>()
  val cleanupFailure = IllegalStateException("cleanup failed")
  val closeFailure = IllegalStateException("session close failed")
  private val stove = Stove()
  private val container = object : StoveCassandraContainer(DockerImageName.parse("cassandra:5.0")) {
    override fun stop() {
      events += "container.stop"
    }
  }

  // The cleanup contract receives a native session; the fixture only models disposal failures.
  private val session = Proxy.newProxyInstance(
    CqlSession::class.java.classLoader,
    arrayOf(CqlSession::class.java)
  ) { _, method, _ ->
    when (method.name) {
      "close" -> {
        events += "session.close"
        throw closeFailure
      }

      else -> error("Unexpected session call: ${method.name}")
    }
  } as CqlSession
  val system = CassandraSystem(
    stove,
    CassandraContext(
      container,
      CassandraSystemOptions(
        cleanup = {
          events += "cleanup"
          throw cleanupFailure
        },
        configureExposedConfiguration = { emptyList() }
      )
    )
  ).apply {
    if (initialize) cqlSession = session
  }

  override fun close() = stove.close()
}
