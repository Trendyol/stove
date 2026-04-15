package com.trendyol.stove.system

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Protocol-agnostic readiness checking strategy for applications under test.
 *
 * Determines how Stove verifies that an application is ready to accept
 * requests before running tests. Supports HTTP, TCP, custom probes,
 * and fixed delays.
 *
 * ## Usage
 *
 * ```kotlin
 * // HTTP health check (REST APIs)
 * ReadinessStrategy.HttpGet(
 *     HealthCheckOptions(url = "http://localhost:8080/health")
 * )
 *
 * // TCP port check (gRPC, raw TCP)
 * ReadinessStrategy.TcpPort(port = 50051)
 *
 * // Custom probe (file existence, DB query, etc.)
 * ReadinessStrategy.Probe { File("/tmp/ready").exists() }
 *
 * // Fixed delay (simple workers)
 * ReadinessStrategy.FixedDelay(3.seconds)
 * ```
 *
 * @see ReadinessChecker
 * @see HealthCheckOptions
 */
sealed interface ReadinessStrategy {
  /**
   * Poll an HTTP GET endpoint until it returns an expected status code.
   *
   * Best for REST APIs and web servers that expose a health endpoint.
   *
   * @param options HTTP health check configuration (URL, retries, timeout, expected status codes).
   */
  data class HttpGet(
    val options: HealthCheckOptions
  ) : ReadinessStrategy

  /**
   * Try to open a TCP connection to a port until it succeeds.
   *
   * Best for gRPC servers, raw TCP servers, and any process that listens
   * on a port but doesn't expose an HTTP health endpoint.
   *
   * @param port The TCP port to connect to.
   * @param retries Number of connection attempts before giving up.
   * @param retryDelay Delay between connection attempts.
   */
  data class TcpPort(
    val port: Int,
    val retries: Int = 30,
    val retryDelay: Duration = 1.seconds
  ) : ReadinessStrategy

  /**
   * Execute a user-provided probe function until it returns `true`.
   *
   * Best for processes with non-standard readiness signals (file existence,
   * database state, custom protocol, etc.).
   *
   * @param retries Number of probe attempts before giving up.
   * @param retryDelay Delay between probe attempts.
   * @param check Suspend function that returns `true` when the process is ready.
   */
  data class Probe(
    val retries: Int = 30,
    val retryDelay: Duration = 1.seconds,
    val check: suspend () -> Boolean
  ) : ReadinessStrategy

  /**
   * Wait a fixed duration before considering the process ready.
   *
   * Fallback for simple workers that don't expose any readiness signal.
   *
   * @param delay Duration to wait.
   */
  data class FixedDelay(
    val delay: Duration = 2.seconds
  ) : ReadinessStrategy
}
