package com.trendyol.stove.container

import com.trendyol.stove.system.Stove
import com.trendyol.stove.system.WithDsl
import com.trendyol.stove.system.abstractions.ReadyStove
import com.trendyol.stove.system.application.ArgsProvider
import com.trendyol.stove.system.application.EnvProvider
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlin.time.Duration.Companion.seconds

class ContainerDslTest :
  FunSpec({
    test("containerApp accepts option elements as parameters") {
      val stove = Stove()

      val readyStove: ReadyStove = WithDsl(stove).containerApp(
        image = "busybox:latest",
        target = ContainerTarget.Worker(),
        command = listOf("echo", "ready"),
        envProvider = EnvProvider.empty(),
        argsProvider = ArgsProvider.empty(),
        gracefulShutdownTimeout = 1.seconds
      )

      readyStove shouldBe stove
    }
  })
