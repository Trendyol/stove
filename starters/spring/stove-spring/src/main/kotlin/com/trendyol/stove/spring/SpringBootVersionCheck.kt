@file:Suppress("TooGenericExceptionCaught", "SwallowedException")

package com.trendyol.stove.spring

/**
 * Utility object to check Spring Boot availability and version at runtime.
 * Since Spring Boot is a `compileOnly` dependency, users must bring their own version.
 */
internal object SpringBootVersionCheck {
  private const val SPRING_APPLICATION_CLASS = "org.springframework.boot.SpringApplication"
  private const val SPRING_BOOT_VERSION_CLASS = "org.springframework.boot.SpringBootVersion"

  /**
   * Checks if Spring Boot is available on the classpath.
   * @throws IllegalStateException if Spring Boot is not found
   */
  fun ensureSpringBootAvailable() {
    try {
      Class.forName(SPRING_APPLICATION_CLASS)
    } catch (e: ClassNotFoundException) {
      throw IllegalStateException(
        """
        |
        |═══════════════════════════════════════════════════════════════════════════════
        |  Spring Boot Not Found on Classpath!
        |═══════════════════════════════════════════════════════════════════════════════
        |
        |  stove-spring-testing-e2e requires Spring Boot to be on your classpath.
        |  Spring Boot is declared as a 'compileOnly' dependency, so you must add it
        |  to your project.
        |
        |  Add one of the following to your build.gradle.kts:
        |
        |  For Spring Boot 2.x:
        |    testImplementation("org.springframework.boot:spring-boot-starter:2.7.x")
        |
        |  For Spring Boot 3.x:
        |    testImplementation("org.springframework.boot:spring-boot-starter:3.x.x")
        |
        |  For Spring Boot 4.x:
        |    testImplementation("org.springframework.boot:spring-boot-starter:4.x.x")
        |
        |═══════════════════════════════════════════════════════════════════════════════
        """.trimMargin(),
        e
      )
    }
  }

  /**
   * Gets the Spring Boot version if available.
   * @return the Spring Boot version string, or "unknown" if not determinable
   */
  fun getSpringBootVersion(): String = try {
    val versionClass = Class.forName(SPRING_BOOT_VERSION_CLASS)
    val getVersionMethod = versionClass.getMethod("getVersion")
    getVersionMethod.invoke(null) as? String ?: "unknown"
  } catch (_: Exception) {
    "unknown"
  }

  /**
   * Gets the major version of Spring Boot.
   * @return the major version (2, 3, 4, etc.) or -1 if not determinable
   */
  fun getSpringBootMajorVersion(): Int = try {
    val version = getSpringBootVersion()
    version.split(".").firstOrNull()?.toIntOrNull() ?: -1
  } catch (e: Exception) {
    -1
  }
}
