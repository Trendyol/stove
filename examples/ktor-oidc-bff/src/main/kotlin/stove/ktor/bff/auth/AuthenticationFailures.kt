package stove.ktor.bff.auth

class LoginRejected(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
class LoginRequired(cause: Throwable? = null) : RuntimeException("Please sign in", cause)
class CsrfRejected : RuntimeException("Invalid CSRF token or request origin")
class ProviderUnavailable(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
