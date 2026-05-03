package com.trendyol.stove.container

import com.trendyol.stove.system.ReadinessStrategy
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class ContainerTargetTest :
  FunSpec({
    test("server target defaults use host port and health readiness") {
      val target = ContainerTarget.Server(hostPort = 8080)

      target.internalPort shouldBe 8080
      target.portEnvVar shouldBe "PORT"
      target.bindHostPort shouldBe true
      (target.readiness is ReadinessStrategy.HttpGet) shouldBe true
      (target.readiness as ReadinessStrategy.HttpGet).url shouldBe "http://localhost:8080/health"
    }

    test("worker target defaults to fixed delay readiness") {
      val target = ContainerTarget.Worker()

      (target.readiness is ReadinessStrategy.FixedDelay) shouldBe true
    }
  })
