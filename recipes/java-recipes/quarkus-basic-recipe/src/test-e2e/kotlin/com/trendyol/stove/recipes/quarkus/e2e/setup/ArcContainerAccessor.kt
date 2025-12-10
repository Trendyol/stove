package com.trendyol.stove.recipes.quarkus.e2e.setup

/**
 * Provides reflection-based access to the Quarkus Arc CDI container.
 *
 * Due to classloader isolation between test and Quarkus runtime,
 * all Arc API access must go through reflection.
 */
object ArcContainerAccessor {

  /**
   * Scans running threads to find and capture the Quarkus classloader.
   * @return true if Arc container is running and classloader captured
   */
  fun isContainerReady(): Boolean {
    for (thread in Thread.getAllStackTraces().keys) {
      val loader = thread.contextClassLoader ?: continue
      if (tryInitializeFromClassLoader(loader)) {
        return true
      }
    }
    return false
  }

  /**
   * Resolves a single bean by class name.
   * @throws IllegalStateException if bean not found
   */
  fun resolveBean(className: String): Any {
    val container = getContainer()
    val targetClass = loadClass(className)
    val instance = selectBean(container, targetClass)

    checkBeanExists(instance, className)
    return getBeanInstance(instance)
  }

  /**
   * Resolves all beans implementing a type.
   * Uses Arc's `listAll()` for multi-resolution.
   */
  fun resolveAllBeans(className: String): List<Any> = QuarkusContext.withContext {
    val container = getContainer()
    val targetClass = loadClass(className)
    val handles = listAllBeans(container, targetClass)

    handles.mapNotNull { handle -> extractBeanFromHandle(handle) }
  }

  // ---- Private helpers ----

  private fun tryInitializeFromClassLoader(loader: ClassLoader): Boolean = try {
    val arcClass = Class.forName("io.quarkus.arc.Arc", false, loader)
    val container = arcClass.getMethod("container").invoke(null) ?: return false
    val isRunning = container.javaClass.getMethod("isRunning").invoke(container) as Boolean

    if (isRunning) {
      QuarkusContext.setClassLoader(loader)
      true
    } else false
  } catch (_: ClassNotFoundException) {
    false
  } catch (_: Exception) {
    false
  }

  private fun getContainer(): Any {
    val arcClass = Class.forName("io.quarkus.arc.Arc", true, QuarkusContext.classLoader)
    return arcClass.getMethod("container").invoke(null)
      ?: error("Arc container not available")
  }

  private fun loadClass(className: String): Class<*> =
    Class.forName(className, true, QuarkusContext.classLoader)

  private fun selectBean(container: Any, targetClass: Class<*>): Any {
    val selectMethod = container.javaClass.getMethod(
      "select",
      Class::class.java,
      Array<Annotation>::class.java
    )
    return selectMethod.invoke(container, targetClass, emptyArray<Annotation>())
  }

  private fun listAllBeans(container: Any, targetClass: Class<*>): List<*> {
    val listAllMethod = container.javaClass.getMethod(
      "listAll",
      Class::class.java,
      Array<Annotation>::class.java
    )
    return listAllMethod.invoke(container, targetClass, emptyArray<Annotation>()) as List<*>
  }

  private fun checkBeanExists(instance: Any, className: String) {
    val isUnsatisfied = instance.javaClass
      .getMethod("isUnsatisfied")
      .invoke(instance) as Boolean

    if (isUnsatisfied) {
      error("No bean found for $className")
    }
  }

  private fun getBeanInstance(instance: Any): Any {
    return instance.javaClass.getMethod("get").invoke(instance)
  }

  private fun extractBeanFromHandle(handle: Any?): Any? {
    if (handle == null) return null
    val getMethod = handle.javaClass.getMethod("get")
    getMethod.isAccessible = true
    return getMethod.invoke(handle)
  }
}
