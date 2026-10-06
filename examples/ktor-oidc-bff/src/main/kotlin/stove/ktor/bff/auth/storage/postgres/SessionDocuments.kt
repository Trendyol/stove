@file:UseSerializers(InstantSerializer::class)

package stove.ktor.bff.auth.storage.postgres

import kotlinx.serialization.*
import kotlinx.serialization.json.Json
import stove.ktor.bff.auth.*
import stove.ktor.bff.auth.storage.*
import stove.ktor.oidc.client.TokenBindingKey
import java.time.Instant

/** Generated serializers own the JSON format; these documents isolate it from runtime session models. */
internal val sessionJson = Json { ignoreUnknownKeys = true }

@Serializable
internal data class LoginDocument(
  val state: String,
  val nonce: String,
  val verifier: String,
  val binding: BindingDocument,
  @Required val format: Int = 1
) {
  init {
    require(format == 1) { "Unsupported session storage format" }
  }

  fun toLogin() = StoredLogin(state, nonce, verifier, binding.toKey())

  companion object {
    fun from(login: StoredLogin) = LoginDocument(login.state, login.nonce, login.verifier, BindingDocument.from(login.binding))
  }
}

@Serializable
internal data class SessionDocument(
  val subject: String,
  val csrfToken: String,
  val nonce: String,
  val binding: BindingDocument,
  val expiresAt: Instant,
  val state: StateDocument,
  @Required val format: Int = 1
) {
  init {
    require(format == 1) { "Unsupported session storage format" }
  }

  fun toSession() = StoredSession(subject, csrfToken, nonce, binding.toKey(), expiresAt, state.toState())

  companion object {
    fun from(session: StoredSession) = SessionDocument(
      session.subject,
      session.csrfToken,
      session.nonce,
      BindingDocument.from(session.binding),
      session.expiresAt,
      StateDocument.from(session.state)
    )
  }
}

@Serializable
internal sealed interface BindingDocument {
  fun toKey(): TokenBindingKey

  @Serializable
  @SerialName("bearer")
  data object Bearer : BindingDocument {
    override fun toKey() = TokenBindingKey.Bearer
  }

  @Serializable
  @SerialName("dpop")
  data class Dpop(val privateJwk: String) : BindingDocument {
    override fun toKey() = TokenBindingKey.Dpop(privateJwk)
  }

  companion object {
    fun from(key: TokenBindingKey): BindingDocument = when (key) {
      TokenBindingKey.Bearer -> Bearer
      is TokenBindingKey.Dpop -> Dpop(key.privateJwk)
    }
  }
}

@Serializable
internal sealed interface StateDocument {
  fun toState(): SessionState

  @Serializable
  @SerialName("ready")
  data class Ready(
    val accessToken: String,
    val expiresAt: Instant,
    val refreshAt: Instant,
    val refreshToken: String? = null
  ) : StateDocument {
    override fun toState() = SessionState.Ready(
      SessionTokens(accessToken, expiresAt, refreshAt, refreshToken?.let(RefreshToken::Available) ?: RefreshToken.Unavailable)
    )
  }

  @Serializable
  @SerialName("refreshing")
  data class Refreshing(val deadline: Instant) : StateDocument {
    override fun toState() = SessionState.Refreshing(deadline)
  }

  companion object {
    fun from(state: SessionState): StateDocument = when (state) {
      is SessionState.Refreshing -> Refreshing(state.deadline)

      is SessionState.Ready -> state.tokens.let {
        Ready(it.accessToken, it.expiresAt, it.refreshAt, (it.refreshToken as? RefreshToken.Available)?.value)
      }
    }
  }
}
