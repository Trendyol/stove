package com.trendyol.stove.quarkus

import com.sun.net.httpserver.HttpServer
import com.trendyol.stove.system.Stove
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.quarkus.runtime.Application
import io.quarkus.runtime.ApplicationLifecycleManager
import kotlinx.coroutines.*
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.milliseconds

class QuarkusApplicationUnderTestTest : FunSpec({
  test("an HTTP response and legacy signal cannot complete startup before Quarkus finishes") {
    val entered = CountDownLatch(1)
    val release = CountDownLatch(1)
    val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    server.createContext("/") { exchange ->
      exchange.sendResponseHeaders(200, -1)
      exchange.close()
    }
    server.start()
    val app = testApplication {
      entered.countDown()
      check(release.await(5, TimeUnit.SECONDS))
    }
    try {
      coroutineScope {
        val startup = async(Dispatchers.IO) {
          app.start(listOf("quarkus.http.port=${server.address.port}", "stove.quarkus.ready=true"))
        }
        try {
          withContext(Dispatchers.IO) { entered.await(5, TimeUnit.SECONDS) } shouldBe true
          delay(600.milliseconds)
          startup.isCompleted shouldBe false
        } finally {
          release.countDown()
        }
        startup.await()
      }
    } finally {
      app.stop()
      server.stop(0)
    }
  }

  test("headless applications can start and stop repeatedly without stale readiness") {
    repeat(2) {
      val app = testApplication {}
      try {
        app.start(emptyList())
        Application.currentApplication().isStarted shouldBe true
      } finally {
        app.stop()
      }
      (Application.currentApplication() == null) shouldBe true
    }
  }

  test("a Quarkus startup failure is reported without exiting the test JVM") {
    val app = testApplication { error("startup observer failed") }
    val error = shouldThrow<IllegalStateException> { app.start(emptyList()) }
    error.message shouldContain "Quarkus startup failed"
    generateSequence<Throwable>(error) { it.cause }
      .joinToString { it.message.orEmpty() } shouldContain "startup observer failed"
  }

  test("a runner that returns without starting Quarkus fails immediately") {
    val app = QuarkusApplicationUnderTest(Stove(), {}, emptyList())
    shouldThrow<IllegalStateException> { app.start(emptyList()) }
      .message shouldContain "exited before application startup completed"
  }

  test("startup still respects the timeout and restores its configuration") {
    val previousTimeout = System.getProperty("stove.quarkus.startup.timeout.ms")
    val app = testApplication { Thread.sleep(600) }
    shouldThrow<IllegalStateException> {
      app.start(listOf("stove.quarkus.startup.timeout.ms=100"))
    }.message shouldContain "Timeout waiting for Quarkus application readiness"
    System.getProperty("stove.quarkus.startup.timeout.ms") shouldBe previousTimeout
  }
})

private fun testApplication(onStart: () -> Unit): QuarkusApplicationUnderTest = QuarkusApplicationUnderTest(
  Stove(),
  {
    ApplicationLifecycleManager.run(object : Application(false) {
      override fun doStart(args: Array<String>) = onStart()

      override fun doStop() = Unit

      override fun getName(): String = "stove-startup-test"
    })
  },
  emptyList()
)
