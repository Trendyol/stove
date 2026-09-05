package com.trendyol.stove.dashboard

import com.trendyol.stove.system.abstractions.SystemOptions
import java.net.URI
import java.nio.file.Path

/** Selects how dashboard events reach the Stove Server. */
sealed interface DashboardIngestion {
  /** Uses the server's plaintext gRPC endpoint. */
  data class Grpc(
    val host: String = "localhost",
    val port: Int = 4041
  ) : DashboardIngestion {
    init {
      require(host.isNotBlank()) { "Dashboard gRPC host must not be blank" }
      require(port in 1..65535) { "Dashboard gRPC port must be between 1 and 65535: $port" }
    }
  }

  /** Uses `POST <baseUrl>/api/v1/events` over HTTP(S). */
  data class Http(val baseUrl: String) : DashboardIngestion {
    internal val eventsUri: URI

    init {
      val uri = runCatching { URI.create(baseUrl) }
        .getOrElse { throw IllegalArgumentException("Invalid dashboard ingestion URL: $baseUrl", it) }
      require(uri.scheme.equals("http", ignoreCase = true) || uri.scheme.equals("https", ignoreCase = true)) {
        "Dashboard ingestion URL must use http or https: $baseUrl"
      }
      require(uri.host != null) { "Dashboard ingestion URL must include a host: $baseUrl" }
      require(uri.rawQuery == null && uri.rawFragment == null) {
        "Dashboard ingestion URL must not include a query or fragment: $baseUrl"
      }
      eventsUri = URI.create("${baseUrl.trimEnd('/')}$EVENTS_PATH")
    }

    private companion object {
      private const val EVENTS_PATH = "/api/v1/events"
    }
  }
}

/**
 * Configuration for the Dashboard system.
 *
 * @param appName Application name shown in the dashboard
 * @param metadata Key-value pairs attached to the run (e.g. team, pipeline id) for filtering in the dashboard
 * @param ingestion Event transport. Defaults to plaintext gRPC at `localhost:4041`.
 */
data class DashboardSystemOptions(
  val appName: String,
  val metadata: Map<String, String> = emptyMap(),
  val ingestion: DashboardIngestion = DashboardIngestion.Grpc(),
  val spool: DashboardSpoolOptions = DashboardSpoolOptions()
) : SystemOptions

/** Persistent producer storage. Use a persistent local directory to recover after restart. */
data class DashboardSpoolOptions(
  val directory: Path = Path.of(System.getProperty("user.home"), ".stove", "spool"),
  val maxBytes: Long = 1024L * 1024 * 1024
) {
  init {
    require(maxBytes >= 1024 * 1024) { "Dashboard spool quota must be at least 1 MiB" }
  }
}
