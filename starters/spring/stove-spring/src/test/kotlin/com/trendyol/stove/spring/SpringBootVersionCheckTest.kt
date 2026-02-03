package com.trendyol.stove.spring

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldNotBe

class SpringBootVersionCheckTest :
  FunSpec({
    test("ensureSpringBootAvailable should not throw when Spring Boot is on classpath") {
      SpringBootVersionCheck.ensureSpringBootAvailable()
    }

    test("getSpringBootVersion should return a non-blank value") {
      SpringBootVersionCheck.getSpringBootVersion().shouldNotBe("unknown")
    }

    test("getSpringBootMajorVersion should parse major version") {
      SpringBootVersionCheck.getSpringBootMajorVersion().shouldBeGreaterThanOrEqual(2)
    }
  })
