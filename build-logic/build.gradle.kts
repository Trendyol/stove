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
    // Expose the publishable Stove plugin locally to the main build.
    kotlin.srcDir("../plugins/stove-gradle-plugin/src/main/kotlin")
    resources.srcDir("../plugins/stove-gradle-plugin/src/main/resources")
  }
}

gradlePlugin {
  plugins {
    create("stoveBuildLogic") {
      id = "com.trendyol.stove.build-logic"
      implementationClass = "com.trendyol.stove.gradle.StoveBuildLogicPlugin"
    }
  }
}

apply(from = "../gradle/stove-plugin.gradle.kts")
