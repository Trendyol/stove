package com.trendyol.stove.extensions.cucumber

import io.cucumber.core.backend.Backend
import io.cucumber.core.backend.Container
import io.cucumber.core.backend.Glue
import io.cucumber.core.backend.Lookup
import io.cucumber.core.backend.discovery.GlueDiscoveryRequest
import io.cucumber.core.backend.discovery.GlueDiscoverySelectorResolver
import io.cucumber.core.resource.ClasspathScanner
import java.lang.reflect.Modifier
import java.util.function.Supplier

internal class StoveBackend(
  private val lookup: Lookup,
  private val container: Container,
  classLoader: Supplier<ClassLoader>
) : Backend {
  private val resolver = GlueDiscoverySelectorResolver(ClasspathScanner(classLoader)) {
    !it.isInterface && !Modifier.isAbstract(it.modifiers)
  }

  override fun loadGlue(glue: Glue, request: GlueDiscoveryRequest) {
    resolver.resolve(request).use { classes ->
      classes.forEach { type ->
        type.requireNoInheritedSteps()
        val definitions = type.declaredMethods
          .filterNot { it.isBridge || it.isSynthetic }
          .mapNotNull { method ->
            method.getAnnotation(Step::class.java)?.let { annotation ->
              SuspendingStepDefinition(method, annotation.value, lookup)
            }
          }
        if (definitions.isNotEmpty()) {
          container.addClass(type)
          definitions.forEach(glue::addStepDefinition)
        }
      }
    }
  }

  private fun Class<*>.requireNoInheritedSteps() {
    generateSequence(superclass) { it.superclass }.forEach { parent ->
      require(parent.declaredMethods.none { it.isAnnotationPresent(Step::class.java) }) {
        "Stove Cucumber steps cannot be inherited: $name extends ${parent.name}"
      }
    }
  }
}
