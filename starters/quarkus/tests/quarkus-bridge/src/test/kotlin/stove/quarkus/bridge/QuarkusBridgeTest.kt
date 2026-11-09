package stove.quarkus.bridge

import com.trendyol.stove.http.HttpClientSystemOptions
import com.trendyol.stove.http.http
import com.trendyol.stove.http.httpClient
import com.trendyol.stove.quarkus.QuarkusBridgeSystem
import com.trendyol.stove.quarkus.bridge
import com.trendyol.stove.quarkus.quarkus
import com.trendyol.stove.system.PortFinder
import com.trendyol.stove.system.Stove
import com.trendyol.stove.system.stove
import com.trendyol.stove.system.using
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.quarkus.arc.Arc

class QuarkusBridgeTest : FunSpec({
  val port = PortFinder.findAvailablePort()
  val textHeaders = mapOf("Accept" to "text/plain")
  lateinit var testStove: Stove

  beforeSpec {
    testStove = Stove()
    testStove.with {
      bridge()
      httpClient { HttpClientSystemOptions(baseUrl = "http://localhost:$port") }
      quarkus(
        runner = { args -> BridgeApplication.main(args) },
        withParameters = listOf("quarkus.http.port=$port")
      )
    }.run()
  }

  afterSpec {
    val bridge = testStove.systemsOf<QuarkusBridgeSystem>().single()
    Stove.stop()
    (Arc.container() == null) shouldBe true
    BridgeFixtures.DependentService.destroyed.get() shouldBe 2
    shouldThrow<IllegalStateException> { bridge.get(CounterService::class) }
      .message shouldContain "Quarkus Bridge is not running"
  }

  test("Bridge and HTTP use the same CDI service and typed return values") {
    stove {
      lateinit var expected: CounterSnapshot
      using<CounterService> {
        expected = increment()
        javaClass.classLoader shouldBe CounterService::class.java.classLoader
        expected.javaClass shouldBe CounterSnapshot::class.java
      }
      http {
        get<String>("/counter", headers = textHeaders) { actual ->
          actual shouldBe "${expected.value()}:${expected.instanceId()}"
        }
        postAndExpectBody<String>("/counter", headers = textHeaders) { actual ->
          actual.status shouldBe 200
          actual.body() shouldBe "${expected.value() + 1}:${expected.instanceId()}"
        }
      }
      using<CounterService> {
        snapshot() shouldBe CounterSnapshot(expected.value() + 1, expected.instanceId())
      }
    }
  }

  test("a bean referenced only by e2e tests is retained by default") {
    stove {
      using<TestOnlyService> { message() shouldBe "retained for e2e" }
    }
  }

  test("Quarkus transformations are loaded instead of raw application classes") {
    // ArC adds this no-arg constructor for its client proxy; the source has only an injected constructor.
    CounterService::class.java.declaredConstructors.any { it.parameterCount == 0 } shouldBe true
    CounterService::class.java.protectionDomain.codeSource.location.path.endsWith("-runner.jar") shouldBe true
  }

  test("generic bean lookup preserves its type arguments") {
    stove {
      using<List<String>> { this shouldBe listOf("quarkus", "bridge") }
    }
  }

  test("dependent instances are distinct and remain alive until Stove stops") {
    stove {
      using<BridgeFixtures.DependentService, BridgeFixtures.DependentService> { first, second ->
        (first === second) shouldBe false
        BridgeFixtures.DependentService.destroyed.get() shouldBe 0
      }
    }
  }

  test("missing and ambiguous beans produce an actionable error") {
    shouldThrow<IllegalStateException> {
      stove { using<Runnable> {} }
    }.message shouldContain "No unique CDI bean found for java.lang.Runnable"
    shouldThrow<IllegalStateException> {
      stove { using<BridgeFixtures.AmbiguousService> {} }
    }.message shouldContain "No unique CDI bean found for stove.quarkus.bridge.BridgeFixtures"
  }
})
