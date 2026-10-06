package stove.ktor.bff.fixtures

import io.ktor.http.content.OutgoingContent
import io.ktor.utils.io.*
import kotlinx.coroutines.CompletableDeferred

/** The test releases the rest only after observing the first bytes at the upstream. */
class GatedUpload(private val remainder: ByteArray = "second".toByteArray()) : OutgoingContent.WriteChannelContent() {
  private val released = CompletableDeferred<Unit>()

  fun finish() {
    released.complete(Unit)
  }

  override suspend fun writeTo(channel: ByteWriteChannel) {
    channel.writeFully("first".toByteArray())
    channel.flush()
    released.await()
    channel.writeFully(remainder)
  }
}
