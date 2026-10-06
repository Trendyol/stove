package stove.ktor.bff.auth.storage

class SessionStorageUnavailable(cause: Throwable) : RuntimeException("Session storage unavailable", cause)
