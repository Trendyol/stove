# OIDC

`stove-oidc` provides an issuer for authentication tests. Choose NAV's in-process mock for controllable JWTs or Keycloak for real client, realm and login policy. Both use the same `OidcSystem`, endpoint metadata and HTTP token client. NAV is the default and does not start Docker.

```kotlin
dependencies {
    testImplementation("com.trendyol:stove-oidc") // version supplied by the Stove BOM
}
```

The module targets Java 17. It pins NAV `6.0.4`, the Keycloak Testcontainers wrapper `4.4.0`, Keycloak admin client `26.0.12` and the default Keycloak image `26.8.0`. Both provider libraries are included in this single artifact. Align older framework dependency-management overrides with these versions, particularly OkHttp, Jackson and Testcontainers.

For a complete browser login example, see the [Ktor OIDC BFF](https://github.com/Trendyol/stove/tree/main/examples/ktor-oidc-bff). It implements authorization code with PKCE, server-side sessions, refresh-token rotation, optional DPoP, profile retrieval and logout. Stove tests run against the JVM app and a GraalVM native executable; dedicated Keycloak scenarios verify DPoP enforcement and refresh rotation. A Keycloak realm is included for manual browser login.

## Register and configure the application

```kotlin
import com.trendyol.stove.oidc.*

Stove().with {
    oidc {
        OidcSystemOptions(
            configureExposedConfiguration = { cfg ->
                listOf("spring.security.oauth2.resourceserver.jwt.issuer-uri=${cfg.issuerUrl}")
            }
        )
    }
    // Register the Spring, Ktor, process or container runner last.
}
```

Stove starts the provider, applies its own configuration, and checks discovery and JWKS before passing configuration to the application. `endpoints` exposes the canonical issuer, discovery URL, JWKS URI, token and authorization endpoints, with optional user-info and logout endpoints. Endpoint addresses come from discovery.

Readiness retries connection failures, request timeouts, HTTP 404/408/429/5xx responses, and empty key sets within `readinessTimeout`. Invalid metadata, issuer mismatches, TLS errors, and other rejected requests fail immediately. Cancellation stops polling.

For a Ktor application, map `cfg.issuerUrl` and `cfg.jwksUri` to the configuration keys consumed by your JWT authentication setup. For a process application, return entries such as `OIDC_ISSUER=${cfg.issuerUrl}` using its existing configuration mapping. The resource server must enforce issuer, audience, signature and token lifetime itself.

Each keyed registration owns its own server or container:

```kotlin
import com.trendyol.stove.system.abstractions.SystemKey

object Corporate : SystemKey
object Partner : SystemKey

Stove().with {
    oidc(Corporate) {
        OidcSystemOptions(OidcProvider.Mock(issuerId = "corporate"))
    }
    oidc(Partner) {
        OidcSystemOptions(OidcProvider.Keycloak(realm = "partner"))
    }
}
```

Default, keyed and mixed providers can coexist. Issuers do not share signing keys or runtime state. Runtime pooling, persistent reuse, federation and broker wiring are outside this module's initial scope.

## Issue controllable mock tokens

```kotlin
import kotlin.time.Duration.Companion.minutes

stove {
    val access = oidc(Corporate) {
        mock.accessToken {
            subject = "user-123"
            audience("orders-api")
            scopes("orders:read")
            expiresIn = 5.minutes
            claim("tenant_id", "tenant-a")
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

`OidcProvider.Mock(defaults = { ... })` supplies defaults for direct token factories and managed rules. Per-call values override them; `audience()` and `scopes()` replace their respective defaults. Nested lists, string-keyed maps, booleans, strings and finite numbers are supported as custom JSON claims. Standard claims must use their typed properties. A null top-level custom claim omits that claim; nested null values are preserved.

The guarded `mock` property supports single operations; `mock { ... }` remains available to group operations. Both reject Keycloak usage before executing token configuration.

ID tokens are separate:

```kotlin
import arrow.core.some

val id = oidc(Corporate) {
    mock.idToken(clientId = "web-app", nonce = "request-nonce".some()) { subject = "user-123" }
}
```

The ID token audience is always the supplied relying-party client id. Its builder omits `audience(...)`; use a different `clientId` for a negative audience test. Rules and provider defaults omit the factory-only `invalidSignature` setting. An ID token is not a bearer access token.

For negative tests, set `expiresIn = (-5).minutes`, `notBefore = Instant.now().plusSeconds(300)`, `audience("wrong-api")`, `issuer = "https://wrong.example"`, or `invalidSignature = true`. The latter signs with a separate unpublished key. These are explicit test token controls; the shared protocol client always uses the selected provider.

RS256 is the tested baseline. The native NAV configuration hook can supply a different token provider or HTTP server, for example `configure = { copy(interactiveLogin = false) }`. Using `copy` preserves Stove’s fresh signing keys; constructing a new native `OAuth2Config()` restores NAV’s bundled static test keys. Other signing algorithms require verification with your application's validator. A fixed `Clock` controls mock factory/rule timestamps; it does not change the application's clock or NAV's native grant clock. Allow for the validator's configured clock skew in expiry tests.

## Request tokens over HTTP

```kotlin
val tokens = oidc(Partner) {
    token(
        TokenRequest.ClientCredentials(
            client = OidcClient("orders-service", ClientAuthentication.SecretPost("test-secret")),
            scopes = setOf("orders:read")
        )
    )
}
```

Supported grants are `ClientCredentials`, `AuthorizationCode` and `RefreshToken`. Client authentication supports `Public`, `SecretBasic` and `SecretPost`. Authorization-code exchange accepts the actual code and redirect URI plus an optional PKCE verifier. Obtaining the code through login is the test/application's responsibility. There is no browser driver in the module.

```kotlin
val tokens = oidc(Partner) {
    token(TokenRequest.AuthorizationCode(
        client = OidcClient("web-app"),
        code = authorizationCode,
        redirectUri = "http://localhost/callback",
        codeVerifier = verifier.some()
    ))
}
```

Responses expose `accessToken`, `tokenType`, and optional ID token, refresh token, lifetime and granted scopes. Access tokens may be opaque. Use `.value` to pass token contents to other clients. Diagnostic rendering includes token values. OAuth failures throw `OidcTokenEndpointException` with `httpStatus`, the provider's `oauthError`, optional `errorDescription`, and the full `responseBody`. Malformed responses retain the parser cause. Requests are never automatically retried or redirected.

NAV requires client authentication for client-credentials grants, but accepts synthetic clients and secrets. It is intentionally permissive. Use Keycloak to test invalid client secrets, client policy and stricter protocol behavior. Passing a NAV test is not evidence of equivalent Keycloak policy.

## Configure Keycloak

Select one `RealmSetup`: `Empty` (the default), `Classpath`, `File`, or `Define`. Import and programmatic definition cannot be combined.

```kotlin
OidcSystemOptions(
    provider = OidcProvider.Keycloak(
        realm = "partner",
        setup = RealmSetup.Define {
            clients = listOf(serviceAccountClient("orders-service", secret = "test-secret"))
        },
        configureAdmin = { realmName ->
            // Managed startup hook: configure this provider's realm using the admin API.
        }
    )
)

// Alternative:
OidcProvider.Keycloak(
    realm = "partner",
    setup = RealmSetup.Classpath("oidc/partner-realm.json")
)
```

`RealmSetup.File(path)` accepts a filesystem JSON file. The realm name in the import or definition must match `realm`. `serviceAccountClient` creates a confidential client with service accounts enabled and browser/password grants disabled; its optional native configuration block can customize those settings. `Define` still receives the native Keycloak realm representation for advanced fixtures.

Stove creates the realm, then calls `configureAdmin`. Container configuration runs before startup. Do not also import the same realm through `configureContainer`, or create it again in the admin hook. Admin clients are closed when startup configuration completes.

Keycloak realm state lasts for the suite. Use unique test users/clients and explicit cleanup for test-created entities. Calling `mock {}` on Keycloak fails before executing the block. No mock token fallback is used.

## Match and observe mock token requests

```kotlin
stove {
    val ordersRule = oidc(Corporate) {
        mock.whenTokenRequested(TokenRequestMatch.clientCredentials(clientId = "orders-test-42")) {
            subject = "alice"
            audience("orders-api")
            claim("roles", listOf("reader"))
        }
    }
    // Exercise the application, which requests a token using orders-test-42.
    ordersRule.shouldHaveBeenCalled(times = 1)
}
```

`OidcTokenRule` verifies only requests routed to that registration in the current test. `calls()` on the handle returns its request evidence; `close()` removes the rule while retaining existing evidence until normal test cleanup. The aggregate `mock.shouldHaveBeenCalled(times, grantType)` remains available for assertions about all visible requests. Assertions are point-in-time: await asynchronous application work first.

Use the named `clientCredentials`, `authorizationCode`, or `refreshToken` matcher factories for standard grants. The `TokenRequestMatch` constructor supports custom grant strings and exact form parameters.

Rules compare client id, grant type and optional form parameters exactly. Test-owned rules override suite rules; the last matching registration within that scope wins. Outside an active Stove test context a rule is a suite fixture. Each registration returns an `OidcTokenRule`, which implements `AutoCloseable` for explicit removal. Stove's test reporting extensions remove test-owned rules at test end and retain evidence until the next test starts.

The shared client propagates Stove's test id. Application OAuth clients often do not. In that case use unique request fields or independent keyed providers. If an untagged request matches rules owned by multiple tests, the mock returns `invalid_request`; it cannot silently choose another test's rule. A single matching owner can be inferred from the discriminator. Untagged evidence with no rule attribution follows Stove's test lifecycle windows.

`calls()` returns grant/status/routing evidence. Routing distinguishes a matched opaque rule ID, fallback, and ambiguous test ownership. Snapshots and failed assertions explain ambiguous ownership without retaining request contents. It does not retain request bodies, headers or tokens. Native refresh tokens retain NAV callback semantics and remain suite state; use fresh authorization sessions per test. Direct factories do not create token-endpoint calls. NAV may return negative `expires_in` for expired endpoint rules; the shared protocol client rejects that metadata. Use direct factories for expired-JWT resource-server tests. Keycloak request observation is not offered.

## Container networking

For a container AUT, set a canonical issuer reachable inside its network, configure the provider to advertise that issuer, and map host-side transport explicitly when needed. Changing only an injected issuer string is insufficient: discovery and token `iss` must agree.

The integration suite verifies this Keycloak setup using a real container client:

```kotlin
val network = Network.newNetwork()
lateinit var runtime: KeycloakContainer

val options = OidcSystemOptions(
    provider = OidcProvider.Keycloak(
        issuerUrl = "http://oidc:8080/realms/stove".some(),
        configureContainer = {
            runtime = this
            withNetwork(network)
            withNetworkAliases("oidc")
            withEnv("KC_HOSTNAME", "http://oidc:8080")
        }
    ),
    resolveEndpoint = { canonical ->
        canonical.replace("http://oidc:8080", runtime.authServerUrl.trimEnd('/'))
    },
    configureExposedConfiguration = { listOf("OIDC_ISSUER=${it.issuerUrl}") }
)
```

Attach the AUT container to the same network and close the externally owned network after Stove shuts down. `resolveEndpoint` affects only Stove's HTTP client; it does not rewrite the exposed metadata or JWT claims.

The shared HTTP client uses Ktor with the CIO engine. `configureHttpClient` receives `HttpClientConfig<CIOEngineConfig>`; configure HTTPS trust through its `engine { https { ... } }` settings. Stove closes the client at shutdown and after failed startup.

For a host NAV server, `bindAddress`, `port` and optional `issuerUrl` support explicit host/container routing; its canonical path must match `issuerId`. See the [container networking guide](22-container.md) for exposing host ports.

## Diagnostics and lifecycle

OIDC diagnostics are designed for test fixtures: token values, client credentials, provider response bodies and error descriptions are available without redaction. Stove reports include operation results and failure messages. `OidcOperationException` adds operation context while preserving the original cause; `OidcConfigurationException` identifies the field and corrective guidance. Invalid rule configuration, such as an infinite lifetime, fails at registration; negative lifetimes remain available for expiry tests. Discovery failures include endpoint and response details, and missing Keycloak imports identify the fixture path. Native provider logging follows the application's logging configuration.

Always pair startup with teardown. The provider cleans its own partially started resources if startup fails; this does not add global rollback guarantees to Stove. When starting Stove manually, retain the local instance and close it in `finally`, including after a failed `run()`.

If cleanup also fails during startup, the startup failure remains primary and the cleanup failure, with its original cause, is attached as a suppressed exception.
