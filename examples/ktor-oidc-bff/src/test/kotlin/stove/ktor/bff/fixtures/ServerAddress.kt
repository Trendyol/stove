package stove.ktor.bff.fixtures

import io.ktor.server.engine.EmbeddedServer
import kotlinx.coroutines.runBlocking

/** Resolve the actual listener after binding to port zero; no socket reservation is released. */
internal fun EmbeddedServer<*, *>.loopbackOrigin(): String = runBlocking {
  "http://127.0.0.1:${engine.resolvedConnectors().single().port}"
}
