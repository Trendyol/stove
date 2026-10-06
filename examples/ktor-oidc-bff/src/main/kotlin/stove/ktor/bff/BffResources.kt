package stove.ktor.bff

import stove.ktor.bff.auth.OidcClient
import stove.ktor.bff.auth.storage.BrowserSessionStore
import stove.ktor.bff.config.BffConfiguration

/** Resources opened at process startup and released together, including partial startup failure. */
internal class BffResources(val provider: OidcClient, val storage: BrowserSessionStore) : AutoCloseable {
  override fun close() {
    try {
      provider.close()
    } finally {
      storage.close()
    }
  }

  companion object {
    suspend fun open(configuration: BffConfiguration): BffResources {
      val provider = OidcClient.connect(configuration)
      try {
        return BffResources(provider, configuration.sessionStorage.open())
      } catch (failure: Throwable) {
        provider.close()
        throw failure
      }
    }
  }
}
