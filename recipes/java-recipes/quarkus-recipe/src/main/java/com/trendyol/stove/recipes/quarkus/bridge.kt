@file:Suppress("UNCHECKED_CAST")

package com.trendyol.stove.recipes.quarkus

import jakarta.enterprise.inject.spi.BeanManager
import java.lang.reflect.Type
import kotlin.reflect.KClass

object StoveQuarkusBridge {
  lateinit var beanManager: BeanManager
  lateinit var classLoader: ClassLoader

  @JvmStatic
  fun set(manager: BeanManager, loader: ClassLoader) {
    beanManager = manager
    classLoader = loader
  }

  @JvmStatic
  fun <T : Any> resolve(kClass: KClass<T>): T = withQuarkusClassLoader {
    beanManager.javaClass
    val bean = beanManager.getBeans(kClass.java).singleOrNull() ?: error("No bean found for ${kClass.java}")
    val contextOfBean = beanManager.createCreationalContext(bean)
    beanManager.getReference(bean, kClass.java, contextOfBean) as T
  }

  @JvmStatic
  fun <T : Any> resolveWithReflection(kClass: KClass<T>): T = withQuarkusClassLoader { originalLoader ->
    val container = this.loadClass("io.quarkus.arc.Arc").getMethod("container").invoke(null)
    val beanManager = container.javaClass.getMethod("beanManager").invoke(container)
    val targetClass = Class.forName(kClass.qualifiedName, true, this)
    val getBeansMethod = beanManager.javaClass.getMethod(
      "getBeans",
      Type::class.java,
      Array<java.lang.annotation.Annotation>::class.java
    )
    val beans =
      getBeansMethod.invoke(beanManager, targetClass, emptyArray<java.lang.annotation.Annotation>()) as Set<*>

    if (beans.isEmpty()) {
      error("No bean found for ${kClass.qualifiedName}")
    }

    val bean = beans.singleOrNull() ?: error("Multiple beans found for ${kClass.qualifiedName}")
    val createContextMethod = beanManager.javaClass.getMethod(
      "createCreationalContext",
      Class.forName("jakarta.enterprise.context.spi.Contextual", true, this)
    )
    val context = createContextMethod.invoke(beanManager, bean)

    val getReferenceMethod = beanManager.javaClass.methods.single {
      it.name == "getReference" && it.parameterCount == 3
    }

    getReferenceMethod.invoke(beanManager, bean, targetClass, context) as T
  }

  @JvmStatic
  private fun <T> withQuarkusClassLoader(action: ClassLoader.(originalLoader: ClassLoader) -> T): T {
    val originalClassLoader = Thread.currentThread().contextClassLoader
    return try {
      Thread.currentThread().contextClassLoader = classLoader
      classLoader.action(originalClassLoader)
    } finally {
      Thread.currentThread().contextClassLoader = originalClassLoader
    }
  }
}
