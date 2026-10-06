package stove.ktor.bff.fixtures

import com.trendyol.stove.system.abstractions.ApplicationUnderTest
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import stove.ktor.bff.resourceapi.ResourceApi

/** Starts after Stove's provider is ready and preserves the selected BFF runner's context. */
class BffWithResourceApi<T : Any>(
  private val bff: ApplicationUnderTest<T>,
  private val requireDpop: Boolean,
  private val apiReady: (String) -> Unit
) : ApplicationUnderTest<T> {
  private var state: State = State.Stopped

  override suspend fun start(configurations: List<String>): T {
    check(state == State.Stopped)
    val issuer = configurations.single { it.startsWith("OIDC_ISSUER=") }.substringAfter('=')
    val api = ResourceApi.start(issuer, requireDpop)
    state = State.Active(api)
    return try {
      apiReady(api.origin)
      val routes = java.nio.file.Path.of(checkNotNull(javaClass.getResource("/gateway-routes.json")).toURI())
      bff.start(configurations + listOf("RESOURCE_API_URL=${api.origin}", "BFF_ROUTES_FILE=$routes"))
    } catch (failure: Throwable) {
      try {
        stop()
      } catch (cleanup: Throwable) {
        failure.addSuppressed(cleanup)
      }
      throw failure
    }
  }

  override suspend fun stop() = withContext(NonCancellable) {
    val active = state as? State.Active ?: return@withContext
    state = State.Stopped
    try {
      bff.stop()
    } catch (failure: Throwable) {
      try {
        active.api.stop()
      } catch (cleanup: Throwable) {
        failure.addSuppressed(cleanup)
      }
      throw failure
    }
    active.api.stop()
  }

  private sealed interface State {
    data object Stopped : State
    data class Active(val api: ResourceApi) : State
  }
}
