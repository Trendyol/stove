package stove.ktor.bff.fixtures

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import stove.ktor.oidc.server.*
import java.util.concurrent.atomic.AtomicInteger

/** Synthetic identities isolate Ktor pipeline behavior; ResourceApiTest checks real signed tokens. */
class ResourceSecurityFixture {
  val handled = AtomicInteger()

  fun install(application: Application) = with(application) {
    install(Authentication) {
      oidc("documents") {
        targetUri = { "https://api.example${it.request.path()}" }
        verifyAccessToken = { credential ->
          val scopes = when (credential) {
            "reader" -> setOf("documents:read")
            "writer" -> setOf("documents:write")
            "editor" -> setOf("documents:read", "documents:write")
            else -> throw ResourceUnauthorized("invalid_token", "Unknown credential")
          }
          VerifiedAccessToken("alice", scopes, AccessBinding.Bearer)
        }
      }
    }
    routing {
      authenticate("documents") {
        requireScopes("documents", "documents:read") {
          get("/documents") { respond(call) }
          requireScopes("documents", "documents:write") {
            post("/documents") { respond(call) }
          }
        }
      }
      requireScopes("documents", "documents:read") {
        get("/misconfigured") { respond(call) }
      }
    }
  }

  private suspend fun respond(call: ApplicationCall) {
    handled.incrementAndGet()
    call.respondText(checkNotNull(call.principal<VerifiedAccessToken>("documents")).subject)
  }
}
