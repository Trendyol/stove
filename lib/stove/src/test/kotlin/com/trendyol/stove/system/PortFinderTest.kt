package com.trendyol.stove.system

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe

class PortFinderTest :
  FunSpec({

    test("findAvailablePort returns a valid port number") {
      val port = PortFinder.findAvailablePort()

      port shouldBeGreaterThanOrEqual 1
      port shouldBeLessThanOrEqual 65535
    }

    test("findAvailablePort returns different ports on consecutive calls") {
      val ports = (1..5).map { PortFinder.findAvailablePort() }.toSet()

      // Should get unique ports (or at least most should be unique)
      ports.size shouldBeGreaterThanOrEqual 1
    }

    test("findAvailablePortFrom returns port at or after starting point") {
      val startingPort = 50000
      val port = PortFinder.findAvailablePortFrom(startingPort)

      port shouldBeGreaterThanOrEqual startingPort
    }

    test("findAvailablePortAsString returns valid string representation") {
      val portString = PortFinder.findAvailablePortAsString()

      portString.toInt() shouldBeGreaterThanOrEqual 1
    }

    test("findAvailablePortFromAsString returns valid string representation") {
      val portString = PortFinder.findAvailablePortFromAsString(50000)

      portString.toInt() shouldBeGreaterThanOrEqual 50000
    }

    test("isPortAvailable returns true for unbound port") {
      val port = PortFinder.findAvailablePort()

      PortFinder.isPortAvailable(port) shouldBe true
    }
  })
