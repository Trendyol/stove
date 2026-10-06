package stove.ktor.bff.tests

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.testApplication
import stove.ktor.bff.fixtures.ResourceSecurityFixture

class ResourceAuthenticationTest : FunSpec({
  test("authentication and scope failures never execute the protected handler") {
    val fixture = ResourceSecurityFixture()
    testApplication {
      application { fixture.install(this) }
      val anonymous = client.get("/documents")
      anonymous.status shouldBe HttpStatusCode.Unauthorized
      anonymous.headers[HttpHeaders.WWWAuthenticate] shouldBe "Bearer error=\"invalid_token\""
      client.get("/documents") { bearerAuth("invalid") }.status shouldBe HttpStatusCode.Unauthorized
      val forbidden = client.get("/documents") { bearerAuth("writer") }
      forbidden.status shouldBe HttpStatusCode.Forbidden
      forbidden.headers[HttpHeaders.WWWAuthenticate].orEmpty() shouldContain "scope=\"documents:read\""
      client.get("/misconfigured").status shouldBe HttpStatusCode.Unauthorized
      fixture.handled.get() shouldBe 0
      client.get("/documents") { bearerAuth("reader") }.bodyAsText() shouldBe "alice"
      fixture.handled.get() shouldBe 1
    }
  }

  test("nested scope requirements preserve both the parent and child policy") {
    val fixture = ResourceSecurityFixture()
    testApplication {
      application { fixture.install(this) }
      for (token in listOf("reader", "writer")) {
        client.post("/documents") { bearerAuth(token) }.status shouldBe HttpStatusCode.Forbidden
      }
      fixture.handled.get() shouldBe 0
      client.post("/documents") { bearerAuth("editor") }.status shouldBe HttpStatusCode.OK
      fixture.handled.get() shouldBe 1
    }
  }
})
