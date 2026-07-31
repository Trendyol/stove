plugins {
  `kotlin-dsl`
}

repositories {
  mavenCentral()
  google()
  gradlePluginPortal()
}

sourceSets {
  main {
    // Expose the publishable tracing plugin locally to the main build.
    kotlin.srcDir("../plugins/stove-tracing-gradle-plugin/src/main/kotlin")
  }
}

gradlePlugin {
  plugins {
    create("stoveBuildLogic") {
      id = "com.trendyol.stove.build-logic"
      implementationClass = "com.trendyol.stove.gradle.StoveBuildLogicPlugin"
    }
    create("stoveTracing") {
      id = "com.trendyol.stove.tracing"
      implementationClass = "com.trendyol.stove.gradle.StoveTracingPlugin"
    }
  }
}
