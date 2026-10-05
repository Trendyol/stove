# OIDC usage

Use `stove-oidc` to supply an issuer for application authentication tests. Check the resolved Stove version before copying these APIs; an installed skill can describe features newer than the application's dependencies. Use the existing framework lifecycle and HTTP setup from [system-setup.md](system-setup.md) and [writing-tests.md](writing-tests.md).

## Choose the provider

| Need | Provider/API |
|---|---|
| Controllable claims, expiry, invalid JWTs, fast tests without Docker | Default `OidcProvider.Mock` (NAV), `mock { accessToken { ... } }` |
| Real realm, client-secret validation, login or client policy | `OidcProvider.Keycloak` (Docker required) |
| Client credentials, authorization-code exchange, refresh | Shared `token(TokenRequest...)` API with either provider |
| Independent issuers or mixed providers | Keyed `oidc(MyKey)` registrations and validation |

Both providers live in one artifact; do not invent a separate Keycloak module. NAV accepts synthetic clients/secrets and is intentionally permissive. Use Keycloak when client policy is the behavior under test. Keycloak rejects `mock {}` before running its block. Federation, broker composition, runtime pooling, and `beforeAppStart` are not implemented.

## Register and expose configuration

Add `testImplementation("com.trendyol:stove-oidc")` to the project's actual test source set, using its Stove BOM/version conventions. Public OIDC APIs use `com.trendyol.stove.oidc.*`.

```kotlin
Stove().with {
    oidc {
        OidcSystemOptions(
            configureExposedConfiguration = { cfg ->
                listOf("spring.security.oauth2.resourceserver.jwt.issuer-uri=${cfg.issuerUrl}")
            }
        )
    }
    // Register the application's existing runner last.
}
```

This is a registration fragment: start and stop Stove through the existing suite lifecycle. The Kotest/JUnit reporting extension alone does not start it. Discovery and JWKS readiness finish before application configuration is supplied. Map `issuerUrl` and `jwksUri` to the keys the application actually consumes; the Spring property above is framework-specific. The application still must validate issuer, audience, signature, and lifetime.

Readiness retries transient transport failures, HTTP 404/408/429/5xx, and empty key sets within `readinessTimeout`. Fix invalid metadata, issuer mismatches, TLS errors, or other rejected requests directly; increasing the readiness timeout will not repair them. Startup retains cleanup failures and their original causes as suppressed exceptions.

For multiple issuers, declare keys and register each in `Stove().with { ... }`:

```kotlin
import com.trendyol.stove.system.abstractions.SystemKey

object Corporate : SystemKey
object Partner : SystemKey

// Inside with:
oidc(Corporate) {
    OidcSystemOptions(OidcProvider.Mock(issuerId = "corporate"))
}
oidc(Partner) {
    OidcSystemOptions(OidcProvider.Keycloak(realm = "partner"))
}
```

Each registration owns its runtime, issuer, and signing keys. Default and keyed systems can coexist. Use the same key in validation. The following examples assume these keys are registered; use `oidc { ... }` for the default system.

## Mint a mock token and call the application

```kotlin
import kotlin.time.Duration.Companion.minutes

stove {
    val access = oidc(Corporate) {
        mock.accessToken {
            subject = "user-123"
            audience("orders-api")
            scopes("orders:read")
            expiresIn = 5.minutes
            claim("roles", listOf("reader"))
        }
    }
    http {
        getBodilessResponse(uri = "/orders", token = access.asHttpToken()) { response ->
            response.status shouldBe 200
        }
    }
}
```

The validation block returns its result. Pass `access.asHttpToken()` to Stove HTTP. This adapter is available only on access tokens; `.value` remains available for other clients. Use `.value` for the raw token; `toString()` includes a diagnostic wrapper. The guarded `mock` property removes a nesting level; `mock { ... }` still groups related operations. Prefer this factory over a custom JWT signer or a native NAV handle.

- `OidcProvider.Mock(defaults = { ... })` supplies factory/rule defaults. Per-call `audience(...)` and `scopes(...)` replace their defaults.
- Use typed properties for standard claims; `claim(name, value)` accepts JSON-compatible custom values. A top-level null omits the claim.
- Negative cases: `expiresIn = (-5).minutes`, future `notBefore`, wrong `audience(...)`, wrong `issuer`, or `invalidSignature = true`. Invalid signatures use an unpublished key and are supported only by direct factories, not request rules.
- ID tokens use `mock.idToken(clientId = "web-app", nonce = "nonce".some()) { subject = "user-123" }`. Their audience is the supplied client ID; the ID-token builder has no audience setter. Use a wrong client ID for a negative audience test. Do not use an ID token as a bearer access token.
- A configured `clock` affects mock factories/rules, not the application's clock or native NAV grants. Account for the application's clock skew in expiry assertions.
- For native NAV customization, use `configure = { copy(interactiveLogin = false) }` to preserve fresh signing keys. A new `OAuth2Config()` restores NAV's bundled static keys. RS256 is the tested baseline.

## Exercise protocol grants

```kotlin
// Inside stove; configure this client in the Keycloak realm first.
val tokens = oidc(Partner) {
    token(TokenRequest.ClientCredentials(
        client = OidcClient("orders-service", ClientAuthentication.SecretPost("test-secret")),
        scopes = setOf("orders:read")
    ))
}
```

Client authentication is `Public`, `SecretBasic(secret)`, or `SecretPost(secret)`. NAV still requires authentication for client credentials. Other grants:

- `TokenRequest.AuthorizationCode(client, code, redirectUri, codeVerifier = verifier.some())`: exchange an actual login code, using the matching redirect URI and PKCE verifier. The module does not drive browser login.
- `TokenRequest.RefreshToken(client, refreshToken)`: supply the `RefreshToken` wrapper extracted from the previous response's optional refresh token.

Responses have `accessToken`, `tokenType`, and optional `idToken`, `refreshToken`, `expiresIn`, and `scopes`. Access tokens may be opaque; do not require JWT decoding in shared-provider helpers. `OidcTokenEndpointException` exposes `httpStatus`, the provider's `oauthError`, optional `errorDescription`, and full `responseBody`. OIDC diagnostics are for test fixtures: preserve token values, credentials, response details and original exception causes when debugging; do not add blanket redaction.

## Configure Keycloak fixtures

Select `setup = RealmSetup.Classpath("oidc/partner-realm.json")`, `RealmSetup.File(path)`, `RealmSetup.Define { ... }`, or the default `RealmSetup.Empty`. One setup value makes import and definition mutually exclusive.

```kotlin
val provider = OidcProvider.Keycloak(
    realm = "partner",
    setup = RealmSetup.Define {
        clients = listOf(serviceAccountClient("orders-service", secret = "test-secret"))
    }
)
// Use OidcSystemOptions(provider = provider) in the Partner registration.
```

`serviceAccountClient` enables confidential client credentials and disables browser/password grants. Its optional native configuration block supports customization. `Define` exposes the native realm representation for other client types, users, roles, and advanced fixtures.

The import/definition's realm name must match the configured realm. Startup order is container configuration → container start → realm creation/import → `configureAdmin` → discovery → application startup. Use the managed `configureAdmin = { realmName -> ... }` hook for additional startup admin work; its receiver is the native Keycloak admin client and Stove closes it afterward. Do not import the same realm again via `configureContainer` or recreate it in the admin hook. Realm state lasts for the suite: use unique test fixtures or explicit cleanup.

## Match and verify application token requests

```kotlin
stove {
    val ordersRule = oidc(Corporate) {
        mock.whenTokenRequested(TokenRequestMatch.clientCredentials(clientId = "orders-test-42")) {
            subject = "alice"
            audience("orders-api")
        }
    }
    // Exercise the AUT using client orders-test-42; await its token request.
    ordersRule.shouldHaveBeenCalled(times = 1)
}
```

Use named `clientCredentials`, `authorizationCode`, and `refreshToken` matcher factories for standard grants. Use the constructor for custom grant strings. Prefer rule-handle assertions when verifying a configured interaction: aggregate `mock.shouldHaveBeenCalled(times, grantType)` also counts other rules and fallback traffic. `ordersRule.calls()` returns only the registration's calls in the current test.

Match client ID, grant type, and optional form `parameters` exactly. Test rules override suite rules; the last match within a scope wins. Registration returns an `OidcTokenRule : AutoCloseable`; closing it removes the rule while retaining its existing evidence until normal test cleanup. Rules registered outside an active Stove test context become suite fixtures.

Test isolation requires `StoveKotestExtension()` or `StoveJUnitExtension` and an active test context. The extension removes test-owned rules at test end and retains evidence until the next test. The shared token client propagates the test ID; application clients often do not. For untagged AUT traffic, use unique request fields or separate keyed issuers. Multiple matching test owners produce `invalid_request`; a single matching owner can be inferred.

Verification is point-in-time; there is no timeout parameter. `calls()` exposes grant/status/disposition evidence: matched opaque rule ID, fallback, or ambiguous test ownership. Failed assertions and snapshots explain ambiguous ownership without storing request payloads. Invalid fixture configuration fails during registration with field guidance. NAV may return negative `expires_in` for expired endpoint rules, which the shared client rejects; use direct factories for expired-JWT resource-server tests. Direct factories produce no token-endpoint calls, and Keycloak has no mock request observation. Native NAV refresh sessions retain suite state and callback semantics; use fresh sessions per test.

## Networking and transport

Container applications need a canonical issuer reachable on their network. Discovery's issuer and token `iss` must match it. For Keycloak, align `issuerUrl`, the container network alias, and `KC_HOSTNAME`; attach the AUT to that network. Use `resolveEndpoint` to map canonical URLs to host-reachable endpoints for Stove's transport. It does not rewrite metadata or JWT claims. For NAV, configure `bindAddress`, `port`, and `issuerUrl`; the canonical path must be `/<issuerId>`.

See [container.md](container.md) and the [complete OIDC networking example](https://trendyol.github.io/stove/Components/23-oidc/#container-networking) before implementing host/container mappings. Close externally owned networks after Stove shuts down.

The managed HTTP transport uses Ktor CIO. `configureHttpClient` receives `HttpClientConfig<CIOEngineConfig>`; use `engine { https { ... } }` for TLS trust configuration. Requests are not automatically retried or redirected. Stove closes the client on shutdown and failed startup. Stove reports include token results and failure details. Native NAV logging follows the application's logging configuration.

## Source checks

In a Stove checkout, verify signatures in `lib/stove-oidc/src/main/kotlin/com/trendyol/stove/oidc/` (`OidcDsl.kt`, `Options.kt`, `Tokens.kt`, `MockOidcControls.kt`). Executable examples live in the matching test directory: `MockOidcTest.kt`, `KeycloakOidcTest.kt`, `OidcLifecycleTest.kt`, and `OidcProtocolClientTest.kt`. For downstream projects, inspect the resolved source artifacts as described in [gradle-config.md](gradle-config.md#resolve-api-ambiguity-from-local-artifacts).
