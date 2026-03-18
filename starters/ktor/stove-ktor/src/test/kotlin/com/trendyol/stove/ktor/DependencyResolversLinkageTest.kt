package com.trendyol.stove.ktor

import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.core.spec.style.FunSpec

class DependencyResolversLinkageTest :
  FunSpec({
    test("autoDetect should load without optional DI libraries on classpath") {
      shouldNotThrowAny {
        DependencyResolvers.autoDetect()
      }
    }
  })
