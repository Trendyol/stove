@file:Suppress("UnstableApiUsage")

plugins {
  `kotlin-dsl`
  `java-gradle-plugin`
  alias(libs.plugins.maven.publish)
}

gradlePlugin {
  plugins {
    create("stoveTracing") {
      id = "com.trendyol.stove.tracing"
      implementationClass = "com.trendyol.stove.gradle.StoveTracingPlugin"
    }
  }
}

tasks.test {
  useJUnitPlatform()
}

dependencies {
  testImplementation(libs.kotest.runner.junit5)
  testImplementation(libs.kotest.framework.engine)
  testImplementation(libs.kotest.assertions.core)
}
