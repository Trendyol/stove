package com.trendyol.stove.gradle

import com.sun.net.httpserver.HttpServer
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.gradle.testkit.runner.TaskOutcome
import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicReference

class WiremockPluginFunctionalTest : FunSpec({
  lateinit var dir: File
  lateinit var server: HttpServer
  lateinit var spec: AtomicReference<String>
  lateinit var types: AtomicReference<String>

  beforeEach {
    dir = testProject()
    server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    spec = server.serve("/api.yaml", resource("supplier.yaml"))
    types = server.serve("/types.yaml", resource("types.yaml"))
    server.start()
    dir.write("build.gradle.kts", resource("consumer.gradle.kts")
      .replace("@kotlinVersion@", System.getProperty("stove.fixture.kotlin"))
      .replace("@port@", server.address.port.toString()))
    dir.write("classpath.txt", System.getProperty("stove.fixture.classpath"))
    for (name in listOf("inventory.yaml", "inventory-models.yaml")) dir.write(name, resource(name))
    for (name in listOf("Consumer.kt", "InventoryScenario.kt")) dir.write("src/contractTest/kotlin/$name", resource(name))
  }

  afterEach {
    server.stop(0)
    dir.deleteRecursively()
  }

  fun run(vararg args: String) = dir.gradle(*args, "--configuration-cache")
  fun compile() = run("compileContractTestKotlin")
  fun generate(vararg options: String) = run("generateSupplierStoveWiremock", *options)

  test("two generated clients exercise separate WireMock servers from a custom source set") {
    val first = run("exercise").build()
    first.output shouldContain "Skipping GET /upload"
    first.task(":stoveExtractPyroscopeExtension") shouldBe null
    run("exercise").build().task(":generateSupplierStoveWiremockSources")?.outcome shouldBe TaskOutcome.UP_TO_DATE
  }

  test("a multi-file Swagger 2 contract serves the same typed client online and offline") {
    spec.set(resource("supplier-v2.yaml"))
    server.serve("/models.yaml", """
      definitions:
        SubmitResponse:
          type: object
          required: [detail]
          properties: { detail: { type: string } }
    """.trimIndent())
    run("exercise").build()
    server.removeContext("/api.yaml")
    server.removeContext("/models.yaml")
    run("clean", "exercise", "--offline").build()
  }

  test("the Kotlin compiler rejects invalid responses and missing path arguments") {
    dir.write("src/contractTest/kotlin/InvalidCalls.kt", resource("InvalidCalls.kt.txt"))
    val errors = compile().buildAndFail().output
    errors shouldContain "ReservationState"
    errors shouldContain "SubmitResponse"
    errors shouldContain "notFound"
    errors shouldContain "No value passed for parameter 'id'"
  }

  test("changes at the same URL invalidate callers and remove stale operations") {
    compile().build()
    types.set(types.get().replace("detail", "message"))
    compile().buildAndFail().output shouldContain "detail"

    types.set(resource("types.yaml"))
    spec.set(spec.get().replace("operationId: submit", "operationId: save"))
    compile().buildAndFail().output shouldContain "submit"
    val generated = dir.resolve("build/generated/stove/wiremock/supplier")
    generated.walk().filter { it.extension == "kt" }.none { "fun submit(" in it.readText() } shouldBe true
  }

  test("nested schema references are fetched relative to their source, but example data is never fetched") {
    types.set(types.get().replace("{ type: string }", "{ \$ref: 'schemas/detail.yaml' }"))
    val detail = server.serve("/schemas/detail.yaml", "type: string\nexample: { \$ref: 'not-a-document.yaml' }")
    generate().build()
    detail.set("\$ref: 'file:///forbidden.yaml'")
    generate().buildAndFail().output shouldContain "A remote OpenAPI document cannot reference a local file"
    server.removeContext("/schemas/detail.yaml")
    generate("--offline").build()
  }

  test("invalid updates and failed downloads preserve the last working offline contract") {
    generate().build()
    val valid = spec.get()
    spec.set(valid.replace("operationId: download", "operationId: submit"))
    generate().buildAndFail().output shouldContain "operationId"
    spec.set(valid.replace("schemas/SubmitResponse", "schemas/Missing"))
    generate().buildAndFail().output shouldContain "Unresolved OpenAPI reference"

    val health = """
      openapi: 3.0.3
      info: {title: Health, version: "1"}
      paths:
        /health:
          get:
            operationId: toString
            responses: { '204': {description: ok} }
    """.trimIndent()
    spec.set(health.substringBefore("responses:") + "responses: 42")
    generate().buildAndFail().output shouldContain "Invalid Stove OpenAPI contract"
    generate("--offline").build()
    spec.set(health)
    generate().buildAndFail().output shouldContain "toString' at GET /health conflicts"

    server.removeContext("/api.yaml")
    generate().buildAndFail().output shouldContain "resolveSupplierStoveContract"
    run("compileContractTestKotlin", "--offline").build()
  }
})

private fun resource(name: String) = fixture("/openapi/$name")

private fun HttpServer.serve(path: String, initial: String) = AtomicReference(initial).also { content ->
  createContext(path) { exchange ->
    val bytes = content.get().toByteArray()
    exchange.sendResponseHeaders(200, bytes.size.toLong())
    exchange.responseBody.use { it.write(bytes) }
  }
}
