package com.trendyol.stove.testing.e2e.kafka

/**
 * Utility object to check Spring Kafka availability at runtime.
 * Since Spring Kafka is a `compileOnly` dependency, users must bring their own version.
 */
internal object SpringKafkaVersionCheck {
  private const val KAFKA_TEMPLATE_CLASS = "org.springframework.kafka.core.KafkaTemplate"

  /**
   * Checks if Spring Kafka is available on the classpath.
   * @throws IllegalStateException if Spring Kafka is not found
   */
  fun ensureSpringKafkaAvailable() {
    try {
      Class.forName(KAFKA_TEMPLATE_CLASS)
    } catch (e: ClassNotFoundException) {
      throw IllegalStateException(
        """
        |
        |═══════════════════════════════════════════════════════════════════════════════
        |  Spring Kafka Not Found on Classpath!
        |═══════════════════════════════════════════════════════════════════════════════
        |
        |  stove-spring-testing-e2e-kafka requires Spring Kafka to be on your classpath.
        |  Spring Kafka is declared as a 'compileOnly' dependency, so you must add it
        |  to your project.
        |
        |  Add one of the following to your build.gradle.kts:
        |
        |  For Spring Boot 2.x:
        |    testImplementation("org.springframework.kafka:spring-kafka:2.9.x")
        |    // or use the starter:
        |    testImplementation("org.springframework.boot:spring-boot-starter-kafka:2.7.x")
        |
        |  For Spring Boot 3.x:
        |    testImplementation("org.springframework.kafka:spring-kafka:3.x.x")
        |    // or use the starter:
        |    testImplementation("org.springframework.boot:spring-boot-starter-kafka:3.x.x")
        |
        |  For Spring Boot 4.x:
        |    testImplementation("org.springframework.kafka:spring-kafka:4.x.x")
        |    // or use the starter:
        |    testImplementation("org.springframework.boot:spring-boot-starter-kafka:4.x.x")
        |
        |═══════════════════════════════════════════════════════════════════════════════
        """.trimMargin(),
        e
      )
    }
  }
}
