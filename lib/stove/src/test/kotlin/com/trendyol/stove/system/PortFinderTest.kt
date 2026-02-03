package com.trendyol.stove.system

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import java.net.ServerSocket

class PortFinderTest :
  FunSpec({
    test("findAvailablePort should return a usable port") {
      val port = PortFinder.findAvailablePort()

      port shouldBeGreaterThan 0
      PortFinder.isPortAvailable(port) shouldBe true
    }

    test("findAvailablePortFrom should skip occupied ports") {
      ServerSocket(0).use { socket ->
        val occupied = socket.localPort
        val found = PortFinder.findAvailablePortFrom(occupied)

        found shouldBeGreaterThan 0
        found shouldBeGreaterThan occupied
      }
    }

    test("findAvailablePortAsString should return numeric string") {
      val portStr = PortFinder.findAvailablePortAsString()

      portStr.toInt() shouldBeGreaterThan 0
    }
  })
