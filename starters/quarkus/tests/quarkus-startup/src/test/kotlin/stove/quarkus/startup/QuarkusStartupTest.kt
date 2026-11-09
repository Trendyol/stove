package stove.quarkus.startup

import com.trendyol.stove.quarkus.QuarkusApplicationUnderTest
import com.trendyol.stove.system.Stove
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.string.shouldContain

class QuarkusStartupTest : FunSpec({
  test("a headless fast-jar starts without application signals and can restart") {
    repeat(2) {
      val app = packagedApplication()
      try {
        app.start(emptyList())
      } finally {
        app.stop()
      }
    }
  }

  test("a fast-jar startup failure reaches the test without terminating its JVM") {
    val app = packagedApplication()
    val failure = shouldThrow<IllegalStateException> {
      app.start(listOf("stove.fixture.fail-startup=true"))
    }
    failure.message shouldContain "Quarkus startup failed"
    generateSequence<Throwable>(failure) { it.cause }
      .joinToString { it.message.orEmpty() } shouldContain "headless startup failed"
  }
})

private fun packagedApplication() = QuarkusApplicationUnderTest(
  Stove(),
  { error("The packaged runtime must launch its own main class") },
  emptyList()
)
