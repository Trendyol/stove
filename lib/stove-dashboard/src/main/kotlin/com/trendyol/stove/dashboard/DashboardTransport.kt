@file:Suppress("TooGenericExceptionCaught")

package com.trendyol.stove.dashboard

import com.trendyol.stove.dashboard.api.DashboardEvent
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
import java.io.IOException
import java.net.URI
import java.util.concurrent.TimeUnit

internal interface DashboardTransport {
  val name: String

  suspend fun send(event: DashboardEvent): SendOutcome

  fun close()
}

internal sealed interface SendOutcome {
  data object Accepted : SendOutcome

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

  override suspend fun send(event: DashboardEvent): SendOutcome = try {
    val ack = stub
      .withDeadlineAfter(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
      .sendEvent(event)
    if (ack.accepted) {
      SendOutcome.Accepted
    } else {
      SendOutcome.Failed(IllegalStateException("Dashboard server did not commit event ${event.eventId}"))
    }
  } catch (error: StatusException) {
    if (error.status.code == Status.Code.INVALID_ARGUMENT) {
      SendOutcome.Rejected(error.status.description ?: "invalid event")
    } else {
      SendOutcome.Failed(error)
    }
  } catch (error: Exception) {
    SendOutcome.Failed(error)
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

  override suspend fun send(event: DashboardEvent): SendOutcome = try {
    val response = client.post(eventsUri.toString()) {
      contentType(PROTOBUF_CONTENT_TYPE)
      accept(PROTOBUF_CONTENT_TYPE)
      setBody(event.toByteArray())
    }
    val responseBody = response.bodyAsBytes()
    when (response.status) {
      HttpStatusCode.OK -> EventAck.parseFrom(responseBody).let { ack ->
        if (ack.accepted) {
          SendOutcome.Accepted
        } else {
          SendOutcome.Failed(IllegalStateException("Dashboard server did not commit event ${event.eventId}"))
        }
      }

      HttpStatusCode.BadRequest -> SendOutcome.Rejected(String(responseBody, Charsets.UTF_8))

      else -> SendOutcome.Failed(IOException("Dashboard server responded with HTTP ${response.status.value}"))
    }
  } catch (error: Exception) {
    SendOutcome.Failed(error)
  }

  override fun close() = client.close()

  private companion object {
    private val PROTOBUF_CONTENT_TYPE = ContentType.parse("application/x-protobuf")
    private const val CONNECT_TIMEOUT_MS = 5_000L
    private const val REQUEST_TIMEOUT_MS = 10_000L
  }
}
