package com.trendyol.stove.testing.e2e.system

import java.net.ServerSocket

/**
 * Utility for finding available ports for test infrastructure.
 *
 * This is useful when running tests in parallel or when default ports
 * might already be in use.
 *
 * Usage:
 * ```kotlin
 * val port = PortFinder.findAvailablePort()
 * // or
 * val port = PortFinder.findAvailablePortFrom(50000)
 * ```
 */
object PortFinder {
  private const val MAX_PORT = 65535
  private const val MIN_PORT = 1024

  /**
   * Finds an available port by letting the OS assign one.
   * This is the most reliable way to find an available port.
   *
   * @return An available port number assigned by the OS
   */
  @JvmStatic
  fun findAvailablePort(): Int = ServerSocket(0).use { it.localPort }

  /**
   * Finds an available port starting from the given port number.
   * Scans ports sequentially until an available one is found.
   *
   * @param startingFrom The port number to start searching from
   * @return An available port number
   * @throws IllegalStateException if no available port is found in the range
   */
  @JvmStatic
  fun findAvailablePortFrom(startingFrom: Int): Int {
    var port = startingFrom
    while (port <= MAX_PORT) {
      if (isPortAvailable(port)) {
        return port
      }
      port++
    }
    port = MIN_PORT
    while (port < startingFrom) {
      if (isPortAvailable(port)) {
        return port
      }
      port++
    }
    error("No available port found in range 1024-$MAX_PORT")
  }

  /**
   * Finds an available port and returns it as a String.
   * Uses OS-assigned port for reliability.
   *
   * @return An available port number as a String
   */
  @JvmStatic
  fun findAvailablePortAsString(): String = findAvailablePort().toString()

  /**
   * Finds an available port starting from the given port and returns it as a String.
   *
   * @param startingFrom The port number to start searching from
   * @return An available port number as a String
   */
  @JvmStatic
  fun findAvailablePortFromAsString(startingFrom: Int): String = findAvailablePortFrom(startingFrom).toString()

  /**
   * Checks if a given port is available for binding.
   *
   * @param port The port to check
   * @return true if the port is available, false otherwise
   */
  @JvmStatic
  fun isPortAvailable(port: Int): Boolean = try {
    ServerSocket(port).use { true }
  } catch (_: Exception) {
    false
  }
}
