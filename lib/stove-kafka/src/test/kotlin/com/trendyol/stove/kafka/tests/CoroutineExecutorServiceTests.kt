package com.trendyol.stove.kafka.tests

import com.trendyol.stove.kafka.*
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.*
import java.util.concurrent.*
import java.util.concurrent.atomic.*

class CoroutineExecutorServiceTests :
  FunSpec({

    test("execute should run the command") {
      val scope = CoroutineScope(Dispatchers.Default + Job())
      val executorService = scope.asExecutorService
      val executed = CountDownLatch(1)

      executorService.execute { executed.countDown() }

      executed.await(2, TimeUnit.SECONDS) shouldBe true
      executorService.shutdown()
    }

    test("shutdown should cancel the coroutine scope") {
      val scope = CoroutineScope(Dispatchers.Default + Job())
      val executorService = scope.asExecutorService

      executorService.isShutdown shouldBe false
      executorService.shutdown()
      executorService.isShutdown shouldBe true
    }

    test("shutdownNow should cancel scope and return empty list") {
      val scope = CoroutineScope(Dispatchers.Default + Job())
      val executorService = scope.asExecutorService

      val result = executorService.shutdownNow()

      result shouldBe emptyList()
      executorService.isShutdown shouldBe true
    }

    test("isTerminated should return true when job is completed") {
      val scope = CoroutineScope(Dispatchers.Default + Job())
      val executorService = scope.asExecutorService

      executorService.isTerminated shouldBe false
      executorService.shutdown()
      // After shutdown, the job is cancelled which means completed
      executorService.isTerminated shouldBe true
    }

    test("awaitTermination should return isTerminated status") {
      val scope = CoroutineScope(Dispatchers.Default + Job())
      val executorService = scope.asExecutorService

      executorService.shutdown()
      executorService.awaitTermination(1, TimeUnit.SECONDS) shouldBe true
    }

    test("execute should run multiple commands") {
      val scope = CoroutineScope(Dispatchers.Default + Job())
      val executorService = scope.asExecutorService
      val counter = AtomicInteger(0)
      val latch = CountDownLatch(3)

      repeat(3) {
        executorService.execute {
          counter.incrementAndGet()
          latch.countDown()
        }
      }

      latch.await(2, TimeUnit.SECONDS) shouldBe true
      counter.get() shouldBe 3
      executorService.shutdown()
    }

    test("StoveCoroutineExecutor should execute commands") {
      val scope = CoroutineScope(Dispatchers.Default + Job())
      val executor = scope.asExecutor
      val executed = AtomicBoolean(false)
      val latch = CountDownLatch(1)

      executor.execute {
        executed.set(true)
        latch.countDown()
      }

      latch.await(2, TimeUnit.SECONDS) shouldBe true
      executed.get() shouldBe true
      scope.cancel()
    }
  })
