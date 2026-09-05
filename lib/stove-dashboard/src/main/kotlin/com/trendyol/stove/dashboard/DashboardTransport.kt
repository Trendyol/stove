@file:Suppress("TooGenericExceptionCaught")

package com.trendyol.stove.dashboard

import com.trendyol.stove.dashboard.api.BatchAck
import com.trendyol.stove.dashboard.api.DashboardEvent
import com.trendyol.stove.dashboard.api.DashboardEventBatch
import com.trendyol.stove.dashboard.api.DashboardEventServiceGrpcKt.DashboardEventServiceCoroutineStub
import com.trendyol.stove.dashboard.api.EventAck
import io.grpc.ManagedChannel
import io.grpc.ManagedChannelBuilder
import io.grpc.Status
import io.grpc.StatusException
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.accept
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.coroutines.CancellationException
import java.io.IOException
import java.net.URI
import java.util.concurrent.TimeUnit

internal interface DashboardTransport {
  val name: String

  suspend fun send(event: DashboardEvent): SendOutcome

  suspend fun sendBatch(batch: DashboardEventBatch): SendOutcome

  fun close()
}

internal sealed interface SendOutcome {
  data object Accepted : SendOutcome

  data object Unsupported : SendOutcome

  data class Rejected(val reason: String) : SendOutcome

  data class Failed(val cause: Exception) : SendOutcome
}

internal fun DashboardIngestion.createTransport(): DashboardTransport = when (this) {
  is DashboardIngestion.Grpc -> GrpcDashboardTransport(host, port)
  is DashboardIngestion.Http -> HttpDashboardTransport(eventsUri)
}

private class GrpcDashboardTransport(
  host: String,
  port: Int
) : DashboardTransport {
  private val channel: ManagedChannel = ManagedChannelBuilder
    .forAddress(host, port)
    .usePlaintext()
    .build()
  private val stub = DashboardEventServiceCoroutineStub(channel)

  override val name: String = "gRPC"

  override suspend fun send(event: DashboardEvent): SendOutcome = request(batch = false) {
    validateEventAck(event, stub.withDeadlineAfter(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS).sendEvent(event))
  }

  override suspend fun sendBatch(batch: DashboardEventBatch): SendOutcome = request(batch = true) {
    validateBatchAck(batch, stub.withDeadlineAfter(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS).sendBatch(batch))
  }

  private suspend fun request(batch: Boolean, block: suspend () -> SendOutcome): SendOutcome = transportAttempt {
    try {
      block()
    } catch (error: StatusException) {
      when (error.status.code) {
        Status.Code.UNIMPLEMENTED -> if (batch) SendOutcome.Unsupported else SendOutcome.Failed(error)
        Status.Code.INVALID_ARGUMENT -> SendOutcome.Rejected(error.status.description ?: if (batch) "invalid batch" else "invalid event")
        else -> SendOutcome.Failed(error)
      }
    }
  }

  override fun close() {
    channel.shutdown()
    try {
      channel.awaitTermination(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)
    } catch (_: InterruptedException) {
      Thread.currentThread().interrupt()
    }
    if (!channel.isTerminated) channel.shutdownNow()
  }

  private companion object {
    private const val REQUEST_TIMEOUT_SECONDS = 10L
    private const val SHUTDOWN_TIMEOUT_SECONDS = 5L
  }
}

private class HttpDashboardTransport(private val eventsUri: URI) : DashboardTransport {
  private val client = HttpClient(CIO) {
    expectSuccess = false
    install(HttpTimeout) {
      connectTimeoutMillis = CONNECT_TIMEOUT_MS
      requestTimeoutMillis = REQUEST_TIMEOUT_MS
    }
  }

  override val name: String = "HTTP"

  override suspend fun send(event: DashboardEvent): SendOutcome = request(eventsUri.toString(), event.toByteArray(), batch = false) {
    validateEventAck(event, EventAck.parseFrom(it))
  }

  override suspend fun sendBatch(batch: DashboardEventBatch): SendOutcome = request("$eventsUri/batch", batch.toByteArray(), batch = true) {
    validateBatchAck(batch, BatchAck.parseFrom(it))
  }

  private suspend fun request(
    uri: String,
    payload: ByteArray,
    batch: Boolean,
    acknowledge: (ByteArray) -> SendOutcome
  ): SendOutcome = transportAttempt {
    val response = client.post(uri) {
      contentType(PROTOBUF_CONTENT_TYPE)
      accept(PROTOBUF_CONTENT_TYPE)
      setBody(payload)
    }
    when {
      response.status == HttpStatusCode.OK -> acknowledge(response.bodyAsBytes())
      batch && response.status in listOf(HttpStatusCode.NotFound, HttpStatusCode.MethodNotAllowed) -> SendOutcome.Unsupported
      response.status == HttpStatusCode.BadRequest -> SendOutcome.Rejected(response.bodyAsBytes().toString(Charsets.UTF_8))
      else -> SendOutcome.Failed(IOException("Dashboard server responded with HTTP ${response.status.value}"))
    }
  }

  override fun close() = client.close()

  private companion object {
    private val PROTOBUF_CONTENT_TYPE = ContentType.parse("application/x-protobuf")
    private const val CONNECT_TIMEOUT_MS = 5_000L
    private const val REQUEST_TIMEOUT_MS = 10_000L
  }
}

private fun validateBatchAck(batch: DashboardEventBatch, ack: BatchAck): SendOutcome {
  val valid = ack.acknowledgementsCount == batch.eventsCount &&
    batch.eventsList.zip(ack.acknowledgementsList).all { (event, result) ->
      result.accepted && result.eventId == event.eventId && result.sequence == event.sequence
    }
  return if (valid) {
    SendOutcome.Accepted
  } else {
    SendOutcome.Failed(IOException("Dashboard batch acknowledgment did not match the pending events"))
  }
}

/** Cancellation belongs to the delivery coroutine; transport failures remain retryable. */
private suspend fun transportAttempt(block: suspend () -> SendOutcome): SendOutcome = try {
  block()
} catch (error: CancellationException) {
  throw error
} catch (error: Exception) {
  SendOutcome.Failed(error)
}

// Older single-event servers may not echo identities; batch acknowledgements require them.
private fun validateEventAck(event: DashboardEvent, ack: EventAck): SendOutcome = if (ack.accepted) {
  SendOutcome.Accepted
} else {
  SendOutcome.Failed(IllegalStateException("Dashboard server did not commit event ${event.eventId}"))
}
