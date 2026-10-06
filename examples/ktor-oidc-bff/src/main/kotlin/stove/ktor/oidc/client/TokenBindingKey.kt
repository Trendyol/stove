package stove.ktor.oidc.client

/** Server-side key material for restoring a session on another process. Never put this in a cookie. */
sealed interface TokenBindingKey {
  fun restore(): TokenBinding

  data object Bearer : TokenBindingKey {
    override fun restore() = TokenBinding.Bearer
  }

  class Dpop(val privateJwk: String) : TokenBindingKey {
    override fun restore() = TokenBinding.Dpop.fromPrivateJwk(privateJwk)
  }
}

fun TokenBinding.storedKey(): TokenBindingKey = when (this) {
  TokenBinding.Bearer -> TokenBindingKey.Bearer
  is TokenBinding.Dpop -> TokenBindingKey.Dpop(privateJwk())
}
