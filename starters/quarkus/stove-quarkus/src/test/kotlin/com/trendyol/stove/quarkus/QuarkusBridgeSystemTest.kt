package com.trendyol.stove.quarkus

import com.trendyol.stove.system.*
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.*
import java.nio.file.Files

class QuarkusBridgeSystemTest : FunSpec({
  test("resolving before startup reports the lifecycle requirement") {
    val bridge = QuarkusBridgeSystem(Stove())
    shouldThrow<IllegalStateException> { bridge.get(String::class) }
      .message shouldContain "Call Stove.run()"
  }

  test("an inaccessible application container explains the required classpath") {
    val bridge = QuarkusBridgeSystem(Stove())
    shouldThrow<IllegalStateException> { bridge.afterRun(Unit) }
      .message shouldContain "put the augmented jar first on the test runtime classpath"
  }

  test("Bridge rejects a fast-jar before launching it") {
    val directory = withContext(Dispatchers.IO) { Files.createTempDirectory("stove-quarkus-fast-jar") }.toFile()
    val originalClasspath = System.getProperty("java.class.path")
    try {
      directory.resolve("build/quarkus-app/quarkus").mkdirs()
      withContext(Dispatchers.IO) { directory.resolve("build/quarkus-app/quarkus/quarkus-application.dat").createNewFile() }
      directory.resolve("build/quarkus-app/lib/boot").mkdirs()
      withContext(Dispatchers.IO) { directory.resolve("build/quarkus-app/lib/boot/boot.jar").createNewFile() }
      System.setProperty("java.class.path", directory.resolve("build/classes").path)
      val stove = Stove()
      stove.withBridgeSystem(QuarkusBridgeSystem(stove))
      val app = QuarkusApplicationUnderTest(stove, { error("Runner must not be invoked") }, emptyList())
      shouldThrow<IllegalStateException> { app.start(emptyList()) }
        .message shouldContain "Quarkus Bridge cannot use the packaged fast-jar runtime"
    } finally {
      System.setProperty("java.class.path", originalClasspath)
      directory.deleteRecursively()
    }
  }
})
