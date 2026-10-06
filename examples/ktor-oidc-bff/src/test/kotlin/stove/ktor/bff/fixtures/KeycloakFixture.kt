package stove.ktor.bff.fixtures

import com.trendyol.stove.oidc.*
import org.keycloak.representations.idm.*

fun keycloakProvider(origin: String): OidcProvider.Keycloak = OidcProvider.Keycloak(
  setup = RealmSetup.Define {
    accessTokenLifespan = 4
    revokeRefreshToken = true
    refreshTokenMaxReuse = 0
    clients = listOf(
      ClientRepresentation().apply {
        clientId = "stove-bff"
        secret = "bff-example-secret"
        protocol = "openid-connect"
        isPublicClient = false
        isStandardFlowEnabled = true
        redirectUris = listOf("$origin/auth/callback")
        // Keycloak's basic scope supplies sub in access tokens for the resource API.
        defaultClientScopes = listOf("basic", "profile", "email")
        protocolMappers = listOf(
          ProtocolMapperRepresentation().apply {
            name = "orders-api-audience"
            protocol = "openid-connect"
            protocolMapper = "oidc-audience-mapper"
            config = mapOf(
              "included.custom.audience" to "orders-api",
              "access.token.claim" to "true",
              "id.token.claim" to "false"
            )
          }
        )
        attributes = mapOf(
          "pkce.code.challenge.method" to "S256",
          "id.token.signed.response.alg" to "RS256",
          "dpop.bound.access.tokens" to "true"
        )
      }
    )
    users = listOf(
      UserRepresentation().apply {
        username = "alice"
        isEnabled = true
        email = "alice@example.com"
        isEmailVerified = true
        firstName = "Alice"
        lastName = "Example"
        credentials = listOf(
          CredentialRepresentation().apply {
            type = "password"
            value = "alice"
            isTemporary = false
          }
        )
      }
    )
  },
  configureAdmin = { name ->
    // Add the scope after realm creation so Keycloak retains its built-in profile/email scopes.
    val realm = realm(name)
    val scope = ClientScopeRepresentation().apply {
      this.name = "orders:read"
      protocol = "openid-connect"
      attributes = mapOf("include.in.token.scope" to "true")
    }
    realm.clientScopes().create(scope).use { check(it.status == 201) }
    val scopeId = realm.clientScopes().findAll().single { it.name == "orders:read" }.id
    val clientId = realm.clients().findByClientId("stove-bff").single().id
    realm.clients().get(clientId).addOptionalClientScope(scopeId)
  }
)
