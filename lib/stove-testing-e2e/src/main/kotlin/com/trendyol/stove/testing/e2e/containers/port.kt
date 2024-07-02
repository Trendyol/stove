package com.trendyol.stove.testing.e2e.containers

import java.net.ServerSocket

object RandomPortFinder {
  /**
   * Finds a random empty port that can be used.
   *
   * @return An available port number
   */
  fun findRandomOpenPort(): Int = ServerSocket(0).use { socket -> socket.localPort }

  /**
   * Finds multiple random empty ports.
   *
   * @param count The number of ports to find
   * @return A list of available port numbers
   */
  fun findRandomOpenPorts(count: Int): List<Int> {
    require(count > 0) { "Count must be greater than 0" }
    return (1..count).map { findRandomOpenPort() }
  }
}
