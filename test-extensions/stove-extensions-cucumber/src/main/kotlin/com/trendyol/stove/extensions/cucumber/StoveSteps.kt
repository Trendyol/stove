package com.trendyol.stove.extensions.cucumber

import com.trendyol.stove.system.ValidationDsl
import com.trendyol.stove.system.stove
import io.cucumber.java8.En
import io.cucumber.java8.LambdaGlue
import io.cucumber.java8.StepDefinitionBody

/**
 * Scenario-scoped lambda glue. Register steps in [body] or init; step bodies suspend inside Stove's validation DSL.
 * Cucumber owns discovery and argument conversion; native hooks inherited from [LambdaGlue] remain synchronous.
 */
abstract class StoveSteps(body: StoveSteps.() -> Unit = {}) : LambdaGlue {
  val Given: StoveStepDsl get() = StoveStepDsl
  val When: StoveStepDsl get() = StoveStepDsl
  val Then: StoveStepDsl get() = StoveStepDsl
  val And: StoveStepDsl get() = StoveStepDsl
  val But: StoveStepDsl get() = StoveStepDsl

  init {
    body()
  }
}

/** Shared registration for every Gherkin keyword, using Cucumber's native lambda backend. */
object StoveStepDsl {
  // Keep the registration delegate private so Cucumber does not discover it as application glue.
  private object Registration : En

  @PublishedApi
  internal fun registration(): En = Registration

  @PublishedApi
  internal fun execute(body: suspend ValidationDsl.() -> Unit) = executeStoveStep { stove(body) }

  inline operator fun invoke(expression: String, crossinline body: suspend ValidationDsl.() -> Unit) {
    registration().Given(expression, StepDefinitionBody.A0 { execute { body() } })
  }

  // Anonymous bodies retain reified argument classes for Cucumber; a generic SAM lambda erases them.
  inline operator fun <reified T1 : Any> invoke(
    expression: String,
    crossinline body: suspend ValidationDsl.(T1) -> Unit
  ) {
    registration().Given(
      expression,
      object : StepDefinitionBody.A1<T1> {
        override fun accept(arg1: T1) = execute {
          body(arg1)
        }
      }
    )
  }

  inline operator fun <reified T1 : Any, reified T2 : Any> invoke(
    expression: String,
    crossinline body: suspend ValidationDsl.(T1, T2) -> Unit
  ) {
    registration().Given(
      expression,
      object : StepDefinitionBody.A2<T1, T2> {
        override fun accept(arg1: T1, arg2: T2) = execute {
          body(arg1, arg2)
        }
      }
    )
  }

  inline operator fun <reified T1 : Any, reified T2 : Any, reified T3 : Any> invoke(
    expression: String,
    crossinline body: suspend ValidationDsl.(T1, T2, T3) -> Unit
  ) {
    registration().Given(
      expression,
      object : StepDefinitionBody.A3<T1, T2, T3> {
        override fun accept(arg1: T1, arg2: T2, arg3: T3) = execute {
          body(arg1, arg2, arg3)
        }
      }
    )
  }

  inline operator fun <reified T1 : Any, reified T2 : Any, reified T3 : Any, reified T4 : Any> invoke(
    expression: String,
    crossinline body: suspend ValidationDsl.(T1, T2, T3, T4) -> Unit
  ) {
    registration().Given(
      expression,
      object : StepDefinitionBody.A4<T1, T2, T3, T4> {
        override fun accept(arg1: T1, arg2: T2, arg3: T3, arg4: T4) = execute {
          body(arg1, arg2, arg3, arg4)
        }
      }
    )
  }

  inline operator fun <reified T1 : Any, reified T2 : Any, reified T3 : Any, reified T4 : Any, reified T5 : Any> invoke(
    expression: String,
    crossinline body: suspend ValidationDsl.(T1, T2, T3, T4, T5) -> Unit
  ) {
    registration().Given(
      expression,
      object : StepDefinitionBody.A5<T1, T2, T3, T4, T5> {
        override fun accept(arg1: T1, arg2: T2, arg3: T3, arg4: T4, arg5: T5) = execute {
          body(arg1, arg2, arg3, arg4, arg5)
        }
      }
    )
  }

  inline operator fun <
    reified T1 : Any,
    reified T2 : Any,
    reified T3 : Any,
    reified T4 : Any,
    reified T5 : Any,
    reified T6 : Any
    > invoke(
    expression: String,
    crossinline body: suspend ValidationDsl.(T1, T2, T3, T4, T5, T6) -> Unit
  ) {
    registration().Given(
      expression,
      object : StepDefinitionBody.A6<T1, T2, T3, T4, T5, T6> {
        override fun accept(arg1: T1, arg2: T2, arg3: T3, arg4: T4, arg5: T5, arg6: T6) = execute {
          body(arg1, arg2, arg3, arg4, arg5, arg6)
        }
      }
    )
  }

  inline operator fun <
    reified T1 : Any,
    reified T2 : Any,
    reified T3 : Any,
    reified T4 : Any,
    reified T5 : Any,
    reified T6 : Any,
    reified T7 : Any
    > invoke(
    expression: String,
    crossinline body: suspend ValidationDsl.(T1, T2, T3, T4, T5, T6, T7) -> Unit
  ) {
    registration().Given(
      expression,
      object : StepDefinitionBody.A7<T1, T2, T3, T4, T5, T6, T7> {
        override fun accept(arg1: T1, arg2: T2, arg3: T3, arg4: T4, arg5: T5, arg6: T6, arg7: T7) = execute {
          body(arg1, arg2, arg3, arg4, arg5, arg6, arg7)
        }
      }
    )
  }

  inline operator fun <
    reified T1 : Any,
    reified T2 : Any,
    reified T3 : Any,
    reified T4 : Any,
    reified T5 : Any,
    reified T6 : Any,
    reified T7 : Any,
    reified T8 : Any
    > invoke(
    expression: String,
    crossinline body: suspend ValidationDsl.(T1, T2, T3, T4, T5, T6, T7, T8) -> Unit
  ) {
    registration().Given(
      expression,
      object : StepDefinitionBody.A8<T1, T2, T3, T4, T5, T6, T7, T8> {
        override fun accept(arg1: T1, arg2: T2, arg3: T3, arg4: T4, arg5: T5, arg6: T6, arg7: T7, arg8: T8) = execute {
          body(arg1, arg2, arg3, arg4, arg5, arg6, arg7, arg8)
        }
      }
    )
  }

  inline operator fun <
    reified T1 : Any,
    reified T2 : Any,
    reified T3 : Any,
    reified T4 : Any,
    reified T5 : Any,
    reified T6 : Any,
    reified T7 : Any,
    reified T8 : Any,
    reified T9 : Any
    > invoke(
    expression: String,
    crossinline body: suspend ValidationDsl.(T1, T2, T3, T4, T5, T6, T7, T8, T9) -> Unit
  ) {
    registration().Given(
      expression,
      object : StepDefinitionBody.A9<T1, T2, T3, T4, T5, T6, T7, T8, T9> {
        override fun accept(arg1: T1, arg2: T2, arg3: T3, arg4: T4, arg5: T5, arg6: T6, arg7: T7, arg8: T8, arg9: T9) = execute {
          body(arg1, arg2, arg3, arg4, arg5, arg6, arg7, arg8, arg9)
        }
      }
    )
  }
}
