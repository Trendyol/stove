package com.trendyol.stove.extensions.cucumber

import io.cucumber.core.backend.CucumberInvocationTargetException
import io.cucumber.core.backend.Lookup
import io.cucumber.core.backend.ParameterInfo
import io.cucumber.core.backend.SourceReference
import io.cucumber.core.backend.StepDefinition
import io.cucumber.core.backend.TypeResolver
import io.cucumber.java.Transpose
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.reflect.Type
import java.util.Optional
import kotlin.reflect.full.callSuspend
import kotlin.reflect.full.extensionReceiverParameter
import kotlin.reflect.jvm.kotlinFunction

internal class SuspendingStepDefinition(
  private val method: Method,
  private val expression: String,
  private val lookup: Lookup
) : StepDefinition {
  private val function = requireNotNull(method.kotlinFunction) { "Stove steps must be Kotlin suspend functions: $method" }

  init {
    require(
      function.isSuspend && function.extensionReceiverParameter == null &&
        Modifier.isPublic(method.modifiers) && !Modifier.isStatic(method.modifiers)
    ) {
      "Stove steps must be public suspend member functions without an extension receiver: $method"
    }
  }

  // Cucumber still converts every argument; only Kotlin's synthetic Continuation is omitted.
  private val parameters = method.parameters.dropLast(1).map { parameter ->
    object : ParameterInfo {
      override fun getType(): Type = parameter.parameterizedType
      override fun isTransposed(): Boolean = parameter.isAnnotationPresent(Transpose::class.java)
      override fun getTypeResolver(): TypeResolver = TypeResolver { type }
    }
  }

  override fun execute(args: Array<out Any>) {
    val instance = lookup.getInstance(method.declaringClass)
    try {
      executeStoveStep { function.callSuspend(instance, *args) }
    } catch (failure: Throwable) {
      // Reflection may wrap a failure before the first suspension. Cucumber must see its original cause.
      val invocation = failure as? InvocationTargetException ?: InvocationTargetException(failure)
      throw CucumberInvocationTargetException(this, invocation)
    }
  }

  override fun parameterInfos(): List<ParameterInfo> = parameters
  override fun getPattern(): String = expression
  override fun getLocation(): String = method.toGenericString()
  override fun getSourceReference(): Optional<SourceReference> = Optional.of(SourceReference.fromMethod(method))
  override fun isDefinedAt(element: StackTraceElement): Boolean =
    element.className == method.declaringClass.name && element.methodName == method.name
}
