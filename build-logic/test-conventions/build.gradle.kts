plugins {
  `kotlin-dsl`
}

repositories {
  mavenCentral()
  gradlePluginPortal()
}

gradlePlugin {
  plugins {
    create("stoveTestConventions") {
      id = "com.trendyol.stove.test-conventions"
      implementationClass = "com.trendyol.stove.gradle.StoveTestConventionPlugin"
    }
  }
}
