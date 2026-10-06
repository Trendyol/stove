package stove.ktor.bff.tests

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.server.testing.testApplication
import stove.ktor.bff.fixtures.gatewayPolicyFixture

class GatewayPolicyTest : FunSpec({
  test("custom calls cannot turn safe browser requests into writes or bypass a more specific method policy") {
    testApplication {
      application { gatewayPolicyFixture() }
      client.get("/safe-mutation").status shouldBe HttpStatusCode.MethodNotAllowed
      val rejected = client.post("/method-denied")
      rejected.status shouldBe HttpStatusCode.MethodNotAllowed
      rejected.headers[HttpHeaders.Allow] shouldBe "GET, HEAD"
    }
  }

  test("custom calls retain payload limits and configured destination boundaries") {
    testApplication {
      application { gatewayPolicyFixture() }
      client.post("/oversized").status shouldBe HttpStatusCode.PayloadTooLarge
      client.get("/unknown").status shouldBe HttpStatusCode.NotFound
      client.get("/traversal").status shouldBe HttpStatusCode.BadRequest
    }
  }
})
