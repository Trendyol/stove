package com.trendyol.stove.cucumberfixtures.lambdaparallel

import com.trendyol.stove.cucumberfixtures.lambdas.assertReceiverContext
import com.trendyol.stove.cucumberfixtures.parallel.ParallelProbe
import com.trendyol.stove.cucumberfixtures.parallel.ParallelSteps
import com.trendyol.stove.extensions.cucumber.StoveSteps
import io.cucumber.java.AfterAll
import io.cucumber.java.BeforeAll

object ParallelLambdaEnvironment {
  @JvmStatic @BeforeAll
  fun start() = ParallelProbe.start()

  @JvmStatic @AfterAll
  fun stop() = ParallelProbe.stop()
}

class ParallelLambdaSteps : StoveSteps({
  val steps = ParallelSteps()
  Given<String, String>("concurrent scenario {word} completes as {word}") { key, outcome ->
    assertReceiverContext()
    steps.concurrent(key, outcome)
  }
  Then("the preceding suspend step has finished") { steps.nextStep() }
  After(io.cucumber.java8.HookNoArgsBody { steps.after() })
})
