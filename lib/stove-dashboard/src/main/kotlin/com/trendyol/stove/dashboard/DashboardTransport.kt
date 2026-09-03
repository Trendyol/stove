@file:Suppress("TooGenericExceptionCaught")

package com.trendyol.stove.dashboard

import com.trendyol.stove.dashboard.api.DashboardEvent
import com.trendyol.stove.dashboard.api.DashboardEventServiceGrpcKt.DashboardEventServiceCoroutineStub
import com.trendyol.stove.dashboard.api.EventAck
import io.grpc.ManagedChannel
import io.grpc.ManagedChannelBuilder
import io.grpc.Status
import io.grpc.StatusException
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
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
    val ack = stub.sendEvent(event)
    if (ack.accepted) {
      SendOutcome.Accepted
    } else {
      SendOutcome.Failed(IllegalStateException("Dashboard CLI did not commit event ${event.eventId}"))
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
    private const val SHUTDOWN_TIMEOUT_SECONDS = 5L
  }
}

private class HttpDashboardTransport(private val eventsUri: URI) : DashboardTransport {
  private val client = HttpClient.newBuilder()
    .connectTimeout(CONNECT_TIMEOUT)
    .build()

  override val name: String = "HTTP"

  override suspend fun send(event: DashboardEvent): SendOutcome = try {
    val response = client.send(
      HttpRequest.newBuilder(eventsUri)
        .timeout(REQUEST_TIMEOUT)
        .header("Content-Type", PROTOBUF_MEDIA_TYPE)
        .header("Accept", PROTOBUF_MEDIA_TYPE)
        .POST(HttpRequest.BodyPublishers.ofByteArray(event.toByteArray()))
        .build(),
      HttpResponse.BodyHandlers.ofByteArray()
    )
    when (response.statusCode()) {
      HTTP_OK -> EventAck.parseFrom(response.body()).let { ack ->
        if (ack.accepted) {
          SendOutcome.Accepted
        } else {
          SendOutcome.Failed(IllegalStateException("Dashboard server did not commit event ${event.eventId}"))
        }
      }

      HTTP_BAD_REQUEST -> SendOutcome.Rejected(String(response.body(), Charsets.UTF_8))

      else -> SendOutcome.Failed(IOException("Dashboard server responded with HTTP ${response.statusCode()}"))
    }
  } catch (error: Exception) {
    SendOutcome.Failed(error)
  }

  override fun close() = Unit

  private companion object {
    private const val PROTOBUF_MEDIA_TYPE = "application/x-protobuf"
    private const val HTTP_OK = 200
    private const val HTTP_BAD_REQUEST = 400
    private val CONNECT_TIMEOUT: Duration = Duration.ofSeconds(5)
    private val REQUEST_TIMEOUT: Duration = Duration.ofSeconds(10)
  }
}
