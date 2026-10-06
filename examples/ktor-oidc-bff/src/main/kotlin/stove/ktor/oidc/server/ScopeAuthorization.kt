package stove.ktor.oidc.server

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.util.AttributeKey

private data class ScopeRequirement(val provider: String, val scopes: Set<String>)
private val RequirementsKey = AttributeKey<List<ScopeRequirement>>("OidcScopeRequirements")

/** Nested requirements accumulate, including when different authentication providers are used. */
fun Route.requireScopes(provider: String, vararg scopes: String, build: Route.() -> Unit): Route {
  require(
    scopes.isNotEmpty() && scopes.all { scope ->
      scope.isNotEmpty() && scope.all { it == '!' || it in '#'..'[' || it in ']'..'~' }
    }
  ) {
    "At least one valid OAuth scope is required"
  }
  val inherited = generateSequence(this) { it.parent }.firstNotNullOfOrNull { it.attributes.getOrNull(RequirementsKey) }.orEmpty()
  val requirements = inherited + ScopeRequirement(provider, scopes.toSet())
  return createChild(ScopeSelector()).apply {
    attributes.put(RequirementsKey, requirements)
    install(ScopeAuthorization) { this.requirements = requirements }
    build()
  }
}

private class ScopeSelector : RouteSelector() {
  override suspend fun evaluate(
    context: RoutingResolveContext,
    segmentIndex: Int
  ): RouteSelectorEvaluation = RouteSelectorEvaluation.Transparent
}
private class ScopeConfiguration {
  var requirements: List<ScopeRequirement> = emptyList()
}
private val ScopeAuthorization = createRouteScopedPlugin("OidcScopeAuthorization", ::ScopeConfiguration) {
  val requirements = pluginConfig.requirements
  on(AuthenticationChecked) { call ->
    if (call.isHandled) return@on
    for (requirement in requirements) {
      val identity = call.principal<VerifiedAccessToken>(requirement.provider)
      if (identity == null) {
        call.oidcProblem(HttpStatusCode.Unauthorized, "invalid_token")
        return@on
      }
      if (identity.scopes.containsAll(requirement.scopes)) continue
      val scheme = if (identity.binding is AccessBinding.Dpop) "DPoP" else "Bearer"
      call.response.header(
        HttpHeaders.WWWAuthenticate,
        "$scheme error=\"insufficient_scope\", scope=\"${requirement.scopes.joinToString(" ")}\""
      )
      call.oidcProblem(HttpStatusCode.Forbidden, "insufficient_scope")
      return@on
    }
  }
}
